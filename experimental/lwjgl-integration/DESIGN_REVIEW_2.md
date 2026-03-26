# Design Review — Compose for Minecraft

Reviewer perspective: Senior software architect with experience in OpenGL rendering pipelines, Minecraft modding (NeoForge/Forge), Compose Multiplatform internals, and game engine integration.

This review covers the full DESIGN.md document and cross-references it against the existing test harness implementation.

---

## Critical Issues

Things that will cause the project to fail or require significant rework if not addressed before implementation begins.

---

### C1. `Minecraft.tell()` dispatches to the game thread, not the render thread

The document states in §1.3:

> `MinecraftCoroutineDispatcher` — dispatches Compose work to Minecraft's render thread via `Minecraft.getInstance().tell(Runnable)`.

This is incorrect. `Minecraft.tell(Runnable)` dispatches to the **game/tick thread** (`Minecraft` extends `ReentrantBlockableEventLoop<Runnable>`). In NeoForge 1.21.1, the game thread and the render thread are the **same thread** — Minecraft is single-threaded for both ticking and rendering. However, the document's phrasing suggests the author may believe these are separate threads with `tell()` specifically routing to the render thread.

This matters because if Minecraft ever moves to a multi-threaded rendering model (as has been discussed in the community), or if a mod like Sodium/Embeddium introduces off-thread rendering, the assumption breaks. The document should be explicit: "Minecraft 1.21.1 is single-threaded — the game thread IS the render thread. `Minecraft.tell()` dispatches to this single thread."

Additionally, the document says in the Technical Decisions table: "Thread safety: Single-threaded (render thread). All Compose work on MC render thread." — this is the correct mental model, but it should acknowledge that `tell()` is a game-loop dispatch, not a render-specific dispatch. The Compose frame clock ticked from `Screen.render()` is what actually ties Compose work to the render phase of the game loop.

**Recommendation:** Clarify the threading model explicitly. Document that `tell()` enqueues work for the next game loop tick, and that `Screen.render()` is what provides render-phase timing.

---

### C2. `AbstractContainerScreen` slot interaction is far more complex than described

The document describes ComposeContainerScreen (§2.4) as:

> Handles the vanilla slot click protocol (shift-click, split, drag-to-distribute) by forwarding to `AbstractContainerMenu.clicked()`

This dramatically understates the complexity. `AbstractContainerScreen` has approximately 800 lines of mouse interaction logic that handles:

- **Carried item rendering** (`renderFloatingItem`) — the item stack following the cursor
- **Quick-craft/drag-to-distribute** — multi-slot drag with left/right/middle button producing different distribution algorithms (even split, one-each, clone). This is a multi-phase state machine tracked by `quickCraftingType`, `quickCraftSlots`, and `quickCraftingButton`.
- **Double-click collection** — double-clicking gathers matching items from all slots
- **Shift-click** — `quickMoveStack()` which varies per container type
- **Slot highlighting/hover** — `findSlot(x, y)` which uses slot pixel coordinates
- **Number key swap** — pressing 1-9 to swap hotbar slots with the hovered slot
- **Drop key** — pressing Q to drop items (or Ctrl+Q for full stack)
- **Creative mode middle-click** — clone stack
- **Off-hand swap** — pressing F to swap to off-hand

All of this logic calls `AbstractContainerMenu.clicked(int slotId, int button, ClickType clickType)` with specific `ClickType` enum values (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`). The Compose UI must either:

1. **Reimplement all of this** in Compose gesture handlers, correctly mapping gestures to `ClickType` values, or
2. **Delegate to the vanilla `AbstractContainerScreen` methods** (`mouseClicked`, `mouseReleased`, `mouseDragged`, `keyPressed`) and let them handle the state machine

Option 2 is the only sane approach, but it means the `ComposeContainerScreen` must preserve a significant amount of vanilla's internal state (`quickCraftingType`, `isSplittingStack`, etc.) and coordinate between Compose's input handling and the vanilla slot interaction state machine.

**Recommendation:** Add a dedicated subsection to Phase 2 acknowledging this complexity. The recommended approach is to let the vanilla `AbstractContainerScreen` input methods execute first (handling slot interactions), and only forward non-consumed input events to the Compose scene. The Compose UI should be purely visual (rendering slots, items, backgrounds) while the vanilla code handles the interaction protocol.

---

### C3. Framebuffer target is hardcoded to FBO 0 — Minecraft uses a managed framebuffer

`BlitPass.blit()` (line 105) calls `glBindFramebuffer(GL_FRAMEBUFFER, 0)`, which targets the default framebuffer. Since Minecraft 1.17, the game renders to a managed `RenderTarget` (not FBO 0). The main framebuffer's ID must be queried from `Minecraft.getMainRenderTarget().frameBufferId`.

The document mentions `NeoForgeGlStateHelper` for save/restore (§1.2) but doesn't mention querying the active framebuffer target. The blit pass must bind Minecraft's active render target, not FBO 0.

Furthermore, during `Screen.render()`, Minecraft may have specific render targets bound depending on what rendering phase is active. The `GuiGraphics` object provided to `render()` has its own buffer management.

**Recommendation:** `BlitPass.blit()` must accept a target framebuffer ID parameter. In the NeoForge integration, this should be obtained from `Minecraft.getMainRenderTarget().frameBufferId` (or from `glGetIntegerv(GL_FRAMEBUFFER_BINDING)` before any state changes). This is a design issue in the core abstraction — the blit pass interface needs to be framebuffer-target-aware.

---

### C4. `PlatformContext.Empty` means no clipboard, no cursor management, no text input method

The `ComposeRenderer` uses `PlatformContext.Empty` when creating the scene (line 129 of `ComposeRenderer.kt`). This means:

- **No clipboard:** `Ctrl+C`, `Ctrl+V`, `Ctrl+X` will silently do nothing in text fields. The document lists "text fields work (typing, selection, Cmd+A, Cmd+C/V/X)" as a Phase 1 deliverable — this will fail.
- **No cursor changes:** The mouse cursor won't change to a text beam when hovering text fields, or to a pointer over clickable elements.
- **No input method (IME):** CJK text input won't work at all.

The document doesn't mention `PlatformContext` anywhere. For Minecraft integration, you need a `PlatformContext` that:
- Implements `ClipboardManager` by delegating to GLFW's `glfwGetClipboardString`/`glfwSetClipboardString`
- Implements `ViewConfiguration` for touch slop, long-press timeout, etc.
- Implements cursor changes via GLFW's `glfwSetCursor` (or via Minecraft's own cursor management if it has one)

**Recommendation:** Add a `MinecraftPlatformContext` to Phase 1 deliverables. At minimum, implement clipboard support and cursor management. Without clipboard, text fields are severely broken.

---

### C5. The dependency graph is inverted — core depends on platform, not the other way around

The document's dependency graph (§3) shows:

```
mod-developer's-mod ──> ui ──> core ──> platform/neoforge-X.Y.Z
```

This means `core` depends on `platform/neoforge-X.Y.Z`, which contradicts the document's own description of `core` as "MC-independent" with "zero MC imports." If `core` depends on a platform module, it transitively depends on NeoForge and Minecraft.

The correct architecture (which the rest of the document actually describes) should be:

```
mod-developer's-mod ──> platform/neoforge-X.Y.Z ──> ui ──> core
```

Or more precisely, the platform module implements interfaces defined in `core`, and the mod developer's mod depends on both the platform module (for the concrete implementations) and `ui`/`core` (for the composables and rendering):

```
                    ┌── ui ──> core (interfaces + rendering, zero MC deps)
mod-developer's-mod ┤
                    └── platform/neoforge-X.Y.Z (implements core interfaces, depends on core + NeoForge)
```

**Recommendation:** Fix the dependency diagram. The platform module depends on core (to implement its interfaces), not the other way around. The mod developer depends on both.

---

### C6. Item rendering via FBO capture will not work as described for container screens

The document says (§3.3, resolved question 4):

> Call MC's `ItemRenderer` to render each `ItemStack` into a small FBO, capture the result as a texture, and display it in Compose as an `Image`.

This approach has severe issues for a container screen with many slots:

1. **Per-item FBO rendering is extremely expensive.** A chest screen has 63 slots (27 chest + 36 player inventory). Rendering each item into a separate FBO means 63 FBO binds, 63 item render passes, and 63 texture reads per frame. This will obliterate frame time.

2. **Minecraft's `ItemRenderer.renderGuiItem()` expects to render into the current `GuiGraphics` context**, not into an arbitrary FBO. It uses `RenderSystem` state, `MultiBufferSource.BufferSource` for batching, and expects specific projection matrices and model-view transforms. Redirecting this to a custom FBO requires manually setting up the entire render state that `GuiGraphics` normally provides.

3. **The enchantment glint effect** is a multi-pass render with a scrolling texture that depends on `RenderSystem.getShaderTexture()` state. Capturing this into a separate FBO while preserving the animation is non-trivial.

4. **Item count overlays and durability bars** are rendered as separate draw calls with specific blend states. The FBO capture must handle all of these sub-passes.

5. **Dirty-flag optimization is insufficient.** Items in container screens change frequently (crafting output updates, furnace progress, etc.), requiring re-capture.

**Recommendation:** Consider a hybrid rendering approach for container screens. Instead of capturing every item into Compose textures, render the Compose UI (backgrounds, labels, buttons) via the Skia pipeline, then overlay Minecraft-native item rendering on top using `GuiGraphics`. The `renderBg` override renders the Compose layer, then the vanilla `renderSlotContents` (or a custom equivalent) renders items natively. This is simpler, faster, and compatible with all modded items.

---

### C7. Escape key handling is unaddressed

The document never mentions how the Escape key is handled. In Minecraft:

- Pressing Escape in a `Screen` calls `Screen.onClose()`, which typically calls `minecraft.setScreen(null)` or returns to the parent screen
- `Screen.keyPressed()` has default handling: if `keyCode == GLFW_KEY_ESCAPE`, it calls `this.onClose()`
- If `ComposeScreen` overrides `keyPressed` to forward to Compose, and Compose doesn't consume the Escape key, the screen should close
- If Compose DOES consume the Escape key (e.g., closing a popup/dropdown within the Compose UI), the screen must NOT close

This interaction between Compose's key event consumption and Minecraft's Escape-to-close behavior needs explicit design. The wrong behavior here will either make screens impossible to close or make it impossible to use Escape for in-UI navigation.

**Recommendation:** `ComposeScreen.keyPressed()` should first forward the key event to Compose. If Compose reports the event as consumed (e.g., a dropdown was dismissed), return `true` (event handled, don't close). If Compose didn't consume it and the key is Escape, call `onClose()`. This requires `ComposeScene.sendKeyEvent()` to return a consumed/not-consumed signal — verify that the API supports this.

---

## Important Concerns

Things that should be addressed in the design but won't necessarily block initial development.

---

### I1. Window resize, minimize, fullscreen toggle, and alt-tab lifecycle

The document mentions resize only in the Phase 1 validation section ("Resize the window while the screen is open, verify layout updates") but doesn't design for the following scenarios:

- **F11 fullscreen toggle:** Changes the framebuffer size. `Screen.resize()` is called, but the ComposeRenderer's `resize()` must also be called. If the resize happens between frames, the FBO size and Compose scene size will be out of sync.
- **Window minimize (iconify):** On Windows, `Screen.render()` continues to be called with zero-size framebuffers when minimized. Rendering to a 0x0 FBO will crash OpenGL. The renderer must guard against zero dimensions.
- **Alt-tab:** On some systems, the GL context may lose resources (though this is rare with modern drivers). More practically, input focus is lost — any held keys should be released in Compose's state.
- **GUI scale change at runtime:** Pressing F8 or changing the GUI scale in options changes `guiScale` mid-session. The `ComposeScreen` must detect this and update Compose's `Density`.
- **Singleplayer pause:** When a singleplayer world is paused (Escape menu), the game tick stops but rendering continues. If `observeMinecraft {}` or `sync()` reads game state that's frozen, this is fine — but if the document's frame clock implementation uses `delta` from `Screen.render()`, the delta will be 0 on every frame while paused. Verify that this doesn't break Compose animations.

**Recommendation:** Add a "Lifecycle Edge Cases" section to Phase 1 that explicitly handles: zero-dimension guard, fullscreen toggle, GUI scale changes, and alt-tab focus loss. Test each scenario.

---

### I2. Multiple Compose surfaces sharing a single `DirectContext` need careful FBO management

The document says (§4.3):

> When a `ComposeHud` and a `ComposeScreen` are active simultaneously, they share the `DirectContext` but have separate FBO/Surface instances.

This is architecturally correct, but the document doesn't address:

- **FBO memory budget:** A 1920x1080 RGBA8 FBO with DEPTH24_STENCIL8 consumes ~12 MB of VRAM. A HUD + screen + overlay means ~36 MB. At 4K, this becomes ~96 MB. With world-space billboards (Phase 7), each entity billboard adds another FBO.
- **Skia `DirectContext` state sharing:** When switching between surfaces backed by different FBOs, `context.resetGL()` must be called between each surface's render. The document covers this for the single-surface case but doesn't describe the multi-surface orchestration.
- **Render ordering:** The HUD renders during the `RenderGuiLayerEvent`, the Screen renders during `Screen.render()`, and the Overlay renders during `ScreenEvent.Render.Post`. These are different points in the frame. Each needs its own GL state save/restore cycle. The document should specify the per-surface render sequence explicitly.

**Recommendation:** Design a `ComposeSurfaceManager` that owns the `DirectContext` and coordinates multiple surfaces. It should track total VRAM usage, handle GL state save/restore between surface renders, and provide an API for registering/unregistering surfaces.

---

### I3. `observeMinecraft {}` with per-frame polling may cause excessive recomposition

The document proposes (§2.3):

> Re-reads the value once per frame via `withFrameNanos`. If the value didn't change, no recomposition.

The implementation sketch is reasonable, but there's a subtlety: `withFrameNanos` suspends until the next frame, reads the value, and if it changed, updates a `mutableStateOf`. But the **comparison** matters. For value types (`Int`, `Float`, `Boolean`), `equals()` works. For reference types (e.g., a `List<ItemStack>`), the default `equals()` may not detect changes if the list is mutated in-place rather than replaced.

Additionally, if many `observeMinecraft {}` calls exist in a single screen, each one resumes a suspended coroutine per frame. With 50 observers, that's 50 coroutine resumptions per frame. This is probably fine for most cases, but it should be documented as a consideration.

**Recommendation:** Document that `observeMinecraft {}` uses structural equality for change detection. Provide guidance on what types work well (primitives, data classes, immutable collections) vs. what types are problematic (mutable collections, objects with reference equality). Consider providing an overload with a custom equality comparator.

---

### I4. LWJGL version conflict between Minecraft and Skiko

Minecraft 1.21.1 bundles LWJGL **3.3.3**. The test harness also uses 3.3.3. But Skiko's native library may link against a different LWJGL version internally, or expect specific LWJGL behavior.

More critically, Minecraft uses LWJGL's OpenGL bindings (`org.lwjgl.opengl.GL*`), and the test harness uses them directly via `GL33C.*`. In the mod environment, these classes are loaded by Minecraft's classloader. If Skiko also uses LWJGL OpenGL bindings internally (it does, for the OpenGL backend on macOS), there could be classloading conflicts if Skiko expects a different package structure or class version.

The document sets `skiko.macos.opengl.enabled=true` to force Skiko to use OpenGL on macOS instead of Metal. This works in the test harness but in the Minecraft mod environment, Skiko's native library loading path may conflict with how NeoForge's module system handles native libraries.

**Recommendation:** Explicitly test Skiko native library loading in the NeoForge classloader environment early in Phase 1. If Skiko uses `System.loadLibrary()` or `System.load()`, verify that NeoForge's `TransformingClassLoader` doesn't interfere. If it does, you may need to use `--add-opens` JVM flags or a custom library loading strategy.

---

### I5. `ComposeScreen` must call `super.render()` — or carefully replicate what it does

The document says (§1.4):

> `render(GuiGraphics, mouseX, mouseY, delta)` — saves GL state, ticks frame clock, renders Compose, blits FBO, restores GL state. **Does NOT call `super.render()`.**

Not calling `super.render()` skips `Screen.render()`, which in NeoForge 1.21.1:

1. Calls `renderBackground(guiGraphics)` if applicable (for the darkened/dirt background)
2. Calls `this.renderables.forEach { it.render(guiGraphics, ...) }` — renders all vanilla widgets added via `addRenderableWidget()`
3. Handles tooltip rendering for focused narrative elements

If ComposeScreen fully replaces all rendering with Compose, skipping `super.render()` is correct and intentional. However, this means:

- **No vanilla `Renderable` widgets can be mixed in.** This is consistent with the "full Compose replacement" design decision, but should be explicitly documented as a consequence.
- **The narrator system expects `this.renderables`** to contain the selectable widgets. Phase 5's narrator bridge must account for this — it can't add synthetic `Selectable` entries to the normal `selectables` list if `super.render()` isn't called, because the list iteration also happens in `super.render()`. The narrator bridge may need to override `narrationEnabled()` and `updateNarrationState()` directly.

**Recommendation:** Verify exactly what `Screen.render()` does in NeoForge 1.21.1 and document each skipped responsibility. For the narrator bridge (Phase 5), override `updateNarrationState(NarrationState)` directly instead of relying on the widget iteration in `super.render()`.

---

### I6. The `runtime-mod` distribution model has unresolved classloading concerns

The document proposes (§3, Runtime Distribution):

> The Compose runtime (Compose UI, Foundation, Skiko native) is shipped as a **standalone library mod** (`runtime-mod`). Other mods that use compose-minecraft declare it as a dependency.

In NeoForge, each mod JAR is loaded by the same `TransformingClassLoader` (unlike Fabric's `KnotClassLoader` which provides per-mod isolation). This means:

- **If two mods bundle different versions of compose-minecraft,** only one version will be loaded (whichever appears first on the classpath). The `runtime-mod` approach avoids this, but only if ALL compose-minecraft mods correctly declare it as a dependency and DON'T shadow/bundle the runtime themselves.
- **Skiko's native libraries** are loaded via `System.loadLibrary()` or equivalent. Native libraries can only be loaded once per JVM. If the `runtime-mod` loads them, and a mod tries to load them independently, you get `UnsatisfiedLinkError: Native Library already loaded in another classloader`.
- **Compose compiler output** is tightly coupled to the Compose runtime version. If `runtime-mod` pins Compose 1.8.2, all mods MUST be compiled with the matching Compose compiler plugin. If a mod developer accidentally uses Compose 1.7.x compiler output, the runtime will crash with `NoSuchMethodError` or similar binary incompatibility errors.

**Recommendation:** The `runtime-mod` should include a version check at load time. When a mod initializes its compose-minecraft integration, verify that the Compose compiler version used to compile the mod matches the runtime version. Provide a clear error message if there's a mismatch. Also document that Skiko native libraries MUST NOT be bundled in individual mod JARs.

---

### I7. `ItemStack` is not stable across Minecraft versions — exposing it directly is risky

The document's "Types Exposed vs. Wrapped" table says:

> `ItemStack` — Expose directly. Fundamental, stable, mod devs work with it everywhere.

While `ItemStack` is indeed fundamental, its internal structure has changed significantly across versions:

- 1.20.5 introduced the component system, replacing NBT-based item data
- `ItemStack` methods like `getTag()` were replaced with `get(DataComponentType<T>)`
- The `count` field handling changed
- `ItemStack.EMPTY` semantics evolved

If the library exposes `ItemStack` directly in the `core` module (which is supposed to be MC-independent), then `core` has a transitive dependency on Minecraft. This contradicts the "zero MC imports" claim for `core`.

If `ItemStack` is only exposed in the `ui` module or the `platform` module, this is less problematic — but the document's module structure shows `ContainerState<T : AbstractContainerMenu>` in `core`, which uses `ItemStack` in its method signatures.

**Recommendation:** Either accept that `core` has Minecraft dependencies (and drop the "zero MC imports" claim), or create an `ItemStackSnapshot` wrapper in `core` that captures the data needed for rendering (item ID, count, damage, display name, component map) without directly referencing `ItemStack`. The platform module converts real `ItemStack` instances to snapshots.

---

### I8. The document doesn't address mod compatibility with other rendering mods

Popular mods that heavily modify rendering include:

- **Sodium / Embeddium / Rubidium** — Replace Minecraft's chunk renderer, modify GL state management, potentially change the active framebuffer during rendering
- **Iris / Oculus** — Shader packs that radically change the rendering pipeline, add additional render passes and FBOs
- **OptiFine** — (Legacy) modifies rendering extensively

These mods may:
- Change which framebuffer is bound during `Screen.render()`
- Modify the GL state in ways that `NeoForgeGlStateHelper.save()/restore()` doesn't account for
- Use the same texture units or uniform bindings that the blit pass uses
- Interfere with `RenderSystem` state tracking

**Recommendation:** Add a "Compatibility" section that acknowledges these mods and describes the testing strategy. At minimum, test with Embeddium (the most popular NeoForge rendering mod for 1.21.1). The GL state save/restore must be robust enough to handle unexpected state from shader mods.

---

### I9. Font strategy has UX implications that should be elevated

The document says:

> Default Skia font for v1. MC bitmap font integration deferred. Skia sans-serif works immediately.

Using a sans-serif system font in a Minecraft UI is not a minor visual mismatch — it fundamentally breaks the "Minecraft-native feel by default" core principle listed at the top of the document. Every component in Phase 3 (`MinecraftButton`, `MinecraftTextField`, etc.) will look wrong with a sans-serif font. The MinecraftTheme becomes a lie.

For a library whose primary value proposition is "UIs that feel native to the game," shipping v1 without the Minecraft font undermines the entire pitch. Mod developers evaluating the library will see non-MC-looking text and dismiss it.

**Recommendation:** Elevate font integration to Phase 1 or early Phase 3. At minimum, load Minecraft's default font texture (`assets/minecraft/textures/font/ascii.png` + `ascii_sga.png`) and create a Skia `Typeface` from it. This is a bitmap font with a fixed grid — converting it to a format Skia can render (a `Typeface` from a glyph atlas) is well-documented. Alternatively, use Minecraft's bundled TrueType fonts (since 1.20, Minecraft bundles Unicode TTF fonts that can be loaded directly by Skia).

---

### I10. `GuiGraphics` is not just a rendering context — it's the entry point for most UI rendering in 1.21.1

The document treats `GuiGraphics` as something to hide/wrap (§ Types Exposed vs. Wrapped: "Wrap / hide — Highly version-dependent"). But in NeoForge 1.21.1, `GuiGraphics` is the primary API for:

- Drawing text (with shadow, with background, centered, etc.)
- Drawing textures (resource locations mapped to sprites)
- Drawing tooltips (with item context for mod tooltip handlers)
- Scissoring (the built-in `enableScissor`/`disableScissor`)
- Item rendering (`renderItem`, `renderItemDecorations`)
- Pose stack management for 2D transforms

While the Compose rendering pipeline replaces most of these, `ComposeContainerScreen` will need `GuiGraphics` for:
- Item rendering (as discussed in C6)
- Tooltip rendering that integrates with NeoForge's `ITooltipExtension` system (mod-provided tooltip components)
- The `renderSlot` callback which mods may override

The `GuiGraphics` instance is passed to `Screen.render()` and is only valid during that call. It cannot be stored.

**Recommendation:** Don't completely hide `GuiGraphics`. Provide an escape hatch where mod developers can access the current frame's `GuiGraphics` for interop with vanilla/modded rendering. This is especially important for container screens where hybrid rendering (Compose + vanilla items) is the practical approach.

---

## Minor Suggestions

Nice-to-haves, style improvements, or things to keep in mind.

---

### M1. `GL_RGBA` vs `GL_RGBA8` in FramebufferObject

`FramebufferObject.configureAttachments()` uses unsized `GL_RGBA` as the internal format:

```kotlin
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL)
```

But `ComposeRenderer.createSkiaSurface()` tells Skia the format is `GR_GL_RGBA8` (sized). Most drivers treat unsized `GL_RGBA` with `GL_UNSIGNED_BYTE` as RGBA8, but the spec doesn't guarantee this. Use `GL_RGBA8` explicitly for the internal format to match what Skia expects:

```kotlin
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL)
```

---

### M2. The blit pass doesn't restore GL state after drawing

`BlitPass.blit()` sets up GL state (disables scissor/stencil/depth, enables blend, binds shader/VAO/texture) but never restores previous state. In the standalone test harness this is fine (the blit is the last thing before swap). In Minecraft, anything that renders after the blit (tooltips, overlays, debug info, other mods' GUI layers) will inherit the blit's GL state.

The document mentions `NeoForgeGlStateHelper.save()/restore()` wrapping the entire Compose render pass, but the save/restore should happen around the complete pass (including the blit), and the restore must cover everything the blit modifies: blend function, bound program, bound VAO, bound texture, active texture unit.

---

### M3. Consider using `glBlitFramebuffer` instead of a custom quad blit

OpenGL 3.0+ provides `glBlitFramebuffer()` which can copy an FBO's color attachment to another framebuffer without a custom shader/quad. This is:
- Simpler (no shader, no VAO, no VBO)
- Potentially hardware-accelerated on some GPUs
- Less GL state pollution

The downside is that `glBlitFramebuffer` doesn't support premultiplied alpha blending — it's a raw copy. So this only works if the blit is the only thing rendering to the target. For overlays that need alpha compositing, the custom quad approach is necessary. But for full-screen `ComposeScreen` that replaces the entire screen content, `glBlitFramebuffer` would be simpler.

---

### M4. The `ComposeResources` holder (§1.6) should own more than just `DirectContext`

The document shows:

```kotlin
class ComposeResources {
    val directContext: DirectContext = DirectContext.makeGL()
    fun close() { directContext.close() }
}
```

This should also own:
- The shared `BlitPass` (shader program + VAO can be reused across surfaces)
- A texture cache for item rendering (Phase 2-3)
- The `MinecraftPlatformContext` with clipboard/cursor support

Making this the central resource holder for all compose-minecraft GL resources simplifies lifecycle management.

---

### M5. Phase ordering: Localization (Phase 5) should be much earlier

`translatedString()` is needed by virtually every UI screen. If Phase 3 builds `MinecraftButton` with text, that text should come from lang files. Deferring localization to Phase 5 means all Phase 3 components will use hardcoded English strings during development, and the localization integration will need to be retrofitted.

**Recommendation:** Move `translatedString()` (the simple lang file bridge) to Phase 1 or early Phase 2. The narrator bridge can stay in Phase 5.

---

### M6. The `ComposeHud.register` and `ComposeOverlay.register` APIs use static registration

The document shows:

```kotlin
ComposeHud.register(ResourceLocation("mymod", "custom_hotbar")) { ... }
```

This looks like a static/global registration. The document explicitly says "Singleton pattern: Avoided" in Technical Decisions. These registration APIs should be scoped to the mod's lifecycle (e.g., registered during mod initialization via NeoForge's event bus, unregistered when the mod is unloaded if hot-reloading is supported).

---

### M7. `ContainerState.sync()` using `ItemStack.copy()` on every slot every frame is wasteful

With 63 slots in a chest screen at 60 FPS, that's 3,780 `ItemStack.copy()` calls per second. Each copy allocates a new `ItemStack` object and deep-copies the component data. Most slots don't change between frames.

**Recommendation:** Compare before copying. Use `ItemStack.matches(other)` (which checks item type and components without copying) to detect changes. Only copy when a change is detected. This reduces allocations by ~95% for typical container screens where most slots are static.

---

### M8. The document should address `Screen.tick()` in addition to `Screen.render()`

`Screen.tick()` is called 20 times per second (once per game tick), separate from `render()` which is called every frame. Some vanilla screens update state in `tick()` (e.g., the cursor blink timer, animated textures). The document should clarify whether the Compose frame clock is only ticked from `render()` (60+ FPS) or also from `tick()` (20 TPS).

The frame clock should only be ticked from `render()` — ticking it from `tick()` at 20 TPS would make animations stutter. But `tick()` is the appropriate place to call `sync()` for state bridging, since game state only changes at tick rate.

**Recommendation:** Call `sync()` from `Screen.tick()` (20 TPS) to update state holders, and tick the Compose frame clock from `Screen.render()` (every frame) for smooth animations.

---

## Questions for the Authors

Things that need clarification or investigation.

---

### Q1. Has Skiko's OpenGL backend been tested on Windows?

The test harness sets `skiko.macos.opengl.enabled=true` to force OpenGL on macOS (where Skiko defaults to Metal). On Windows and Linux, Skiko defaults to OpenGL — but has this been verified? Skiko's OpenGL backend has historically been less tested than Metal (macOS) and Direct3D (Windows). The `DirectContext.makeGL()` call should work, but there may be driver-specific issues.

Minecraft 1.21.1 requires OpenGL 3.2 core profile. Skiko should be compatible with this, but it's worth verifying that Skiko doesn't require any extensions beyond what Minecraft's minimum spec provides.

---

### Q2. What happens when two mods both register a `ComposeHud` that replaces the vanilla hotbar?

The document says ComposeHud "Can optionally cancel vanilla layers it replaces." If mod A and mod B both register a HUD that cancels the vanilla hotbar and provides their own, which one wins? NeoForge's `RegisterGuiLayersEvent` has ordering (`above`/`below` other layers), but two mods canceling the same vanilla layer will conflict.

Is there a conflict resolution strategy, or is this a "don't do that" documentation issue?

---

### Q3. How does the `CanvasLayersComposeScene` behave when the window is not focused?

When Minecraft's window is not focused (alt-tabbed away), `Screen.render()` may still be called (Minecraft continues rendering in the background by default, configurable via "Pause on Lost Focus"). Does `CanvasLayersComposeScene.render()` handle this gracefully? Are there assumptions about GLFW events continuing to arrive?

Additionally, if the user alt-tabs while holding a key or mouse button, Compose may never receive the release event. This leaves internal state (like a pressed button state) stuck. The document should address how to handle focus loss — potentially by sending synthetic release events for all pressed buttons/keys when focus is lost.

---

### Q4. What is the memory overhead of a `CanvasLayersComposeScene` + `DirectContext`?

The document claims "static HUD costs essentially nothing" but doesn't quantify:
- How much memory does a `DirectContext` consume? (It caches shaders, textures, render targets internally)
- How much memory does a `CanvasLayersComposeScene` consume at rest? (The Compose node tree, layout cache, semantics tree)
- With a HUD + screen + overlay, what's the baseline GPU memory footprint before any content is rendered?

This would help mod developers understand the cost of adopting the library.

---

### Q5. Has `CanvasLayersComposeScene` been verified as the correct scene type?

The code uses `CanvasLayersComposeScene`, which is marked as `@InternalComposeUiApi`. This means:
- It can change or be removed without notice between Compose versions
- Its behavior and contract are not documented for external consumers

Is there a public API alternative? `ComposeScene` (the older API) was deprecated in favor of `MultiLayerComposeScene` in some Compose versions. The choice of scene implementation should be documented with rationale and a migration plan if the internal API changes.

---

### Q6. How will the library handle multiple Minecraft versions simultaneously?

The document shows `platform/neoforge-1.21.1/` and `platform/neoforge-1.21.4/` as separate modules. But:
- Will both modules exist in the same repository and build simultaneously?
- Will they share the same `core` and `ui` modules?
- How will the Gradle build handle different Minecraft dependency versions?
- Will mod developers need to choose the correct platform module at compile time?

NeoForge's version numbering (1.21.1 vs 1.21.4) implies API differences. The `core` module's interfaces must be designed broadly enough to accommodate both. If a method exists in 1.21.4 but not 1.21.1, the accessor interface must handle this gap.

---

### Q7. What is the plan for testing the GL state save/restore correctness?

The document lists "no GL state corruption" as a validation criterion but doesn't describe how to verify this. Manual visual inspection is unreliable — subtle state corruption (e.g., wrong blend function on a specific vanilla GUI element) may not be immediately obvious.

**Suggestion:** Create a diagnostic mode that dumps the full GL state (all `glGet*` queries for the state categories the library touches) before save, after save, and after restore. Compare the pre-save and post-restore states programmatically. This can be a dev-only tool, not shipped in production.

---

### Q8. Security: Can `observeMinecraft {}` expose server-side data to the client?

The document's `observeMinecraft {}` escape hatch lets mod developers read arbitrary Minecraft state. In a multiplayer context, the client only has access to data the server has sent. But some `LocalPlayer` fields contain server-authoritative data that the client shouldn't display (or that anti-cheat mods might flag):

- Reading other entities' exact health values (only available via the `EntityData` synced by the server — normally displayed as hearts above the entity)
- Accessing inventory contents of containers not currently open (the server doesn't send this data, so this would just read stale/empty data, but the API might suggest it's possible)

This isn't a vulnerability in compose-minecraft itself (it can't read data the client doesn't have), but the API should document that `observeMinecraft {}` only accesses client-side state and cannot be used to gain information the client shouldn't have.

---

### Q9. What happens to the Compose scene when `Screen.resize()` is called?

Minecraft calls `Screen.resize(minecraft, width, height)` when the window is resized. The default implementation calls `Screen.rebuildWidgets()`, which calls `clearWidgets()` then `init()`. If `ComposeScreen.init()` creates a new `ComposeRenderer` and `ComposeScene`, the entire Compose tree is destroyed and recreated on every resize.

This means:
- All Compose state (`remember`, `mutableStateOf`) is lost on resize
- Any ongoing animations restart
- Text field content is cleared
- Scroll positions reset

This is catastrophic for user experience during a resize. The `ComposeRenderer.resize()` method exists in the test harness specifically to handle this without recreating the scene. But if `Screen.resize()` calls `init()` which calls `createScene()`, the resize path bypasses `ComposeRenderer.resize()`.

**Recommendation:** Override `Screen.resize()` in `ComposeScreen` to call `composeRenderer.resize()` instead of the default `rebuildWidgets()` flow. Do NOT recreate the Compose scene on resize.

---

### Q10. Does the design account for Minecraft's `PoseStack` / matrix state during Screen rendering?

When `Screen.render(GuiGraphics, mouseX, mouseY, delta)` is called, `GuiGraphics` has an active `PoseStack` that may have been transformed (scaled by `guiScale`, potentially translated). The Compose rendering pipeline assumes it's rendering to a framebuffer at pixel coordinates, but if Minecraft's model-view matrix is not identity when the blit happens, the quad will be rendered at the wrong position/scale.

The blit pass uses NDC coordinates (-1 to +1) which bypass the model-view matrix, but only if the blit uses its own shader (which it does). However, after the blit, if Minecraft expects the `PoseStack` to be in a specific state, and the blit pass dirtied any matrix state via `RenderSystem`, there could be issues.

**Recommendation:** Before blitting, save `RenderSystem`'s matrix state. After blitting, restore it. Or verify that the custom shader pipeline (which doesn't use `RenderSystem.setShader()`) is completely independent of Minecraft's matrix stack.
