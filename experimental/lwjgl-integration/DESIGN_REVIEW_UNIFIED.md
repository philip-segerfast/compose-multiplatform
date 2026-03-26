# Unified Design Review — Compose for Minecraft

Consolidated from two independent reviews of `DESIGN.md` and the test harness implementation.

---

## Critical Issues

Things that will cause the project to fail or require significant rework if not addressed before implementation begins.

---

### C1. The dependency graph is inverted — `core` cannot depend on `platform`

The document's dependency graph (§3) shows:

```
mod-developer's-mod ──> ui ──> core ──> platform/neoforge-X.Y.Z
```

But `core` contains the abstract/interface types (`ComposeScreen`, `ComposeContainerScreen`) and `platform` contains the implementations (`NeoForgeComposeScreen`). The arrow `core → platform` implies `core` has a compile-time dependency on the platform module, which defeats the purpose of the abstraction layer and transitively makes `core` depend on NeoForge and Minecraft — contradicting the document's own description of `core` as "MC-independent" with "zero MC imports."

The correct architecture should be:

```
                    ┌── ui ──> core (interfaces + rendering, zero MC deps)
mod-developer's-mod ┤
                    └── platform/neoforge-X.Y.Z (implements core interfaces, depends on core + NeoForge)
```

Where the platform module depends on `core` (to implement its interfaces), not the other way around. The mod developer depends on both. The platform module is wired in at runtime via NeoForge's mod loading, service loaders, or manual DI.

**Recommendation:** Fix the dependency diagram. This affects how every module is structured.

---

### C2. The `core` module cannot have "zero MC imports" as claimed

The architecture diagram (§1) and module structure (§3) claim `core` has "zero MC imports" and contains the API interfaces. But the document simultaneously says `ItemStack`, `ResourceLocation`, `Component`, and `AbstractContainerMenu` are exposed directly (Types Exposed vs. Wrapped table). The `LocalPlayerAccessor` interface in `core` has `val mainHandItem: ItemStack` — that's a Minecraft import. `ComposeContainerScreen` is generic over `AbstractContainerMenu` — another MC import. `ContainerState<T : AbstractContainerMenu>` in `core` uses `ItemStack` in its method signatures.

Additionally, `ItemStack` is not stable across Minecraft versions — 1.20.5 introduced the component system, replacing NBT-based item data. Methods like `getTag()` were replaced with `get(DataComponentType<T>)`, and `count` field handling changed.

This means `core` cannot be MC-independent. Either:
- Split `core` into `core-render` (truly MC-independent: renderer, FBO, blit, shaders) and `core-api` (depends on MC for type references), or
- Accept that `core` depends on MC (at compile time, against a specific MC version), which undermines the version-isolation goal, or
- Replace all MC types in the API with abstractions (e.g., `ItemStackSnapshot` data class that captures item ID, count, damage, display name, component map), which adds complexity and loses the "expose stable types directly" benefit.

**Recommendation:** This is a fundamental tension in the module design that needs resolution before implementation begins. Either accept the MC dependency and drop the "zero MC imports" claim, or introduce wrapper types and move MC-referencing interfaces to the platform module.

---

### C3. `glBindFramebuffer(GL_FRAMEBUFFER, 0)` in `BlitPass` will not work in Minecraft

`BlitPass.blit()` hardcodes framebuffer 0 as the render target. Since Minecraft 1.17, the game renders to a managed `RenderTarget` (not FBO 0). The main framebuffer's ID must be queried from `Minecraft.getMainRenderTarget().frameBufferId`. With shader mods (Iris, Optifine, Sodium/Embeddium), additional framebuffers are in play.

Furthermore, during `Screen.render()`, Minecraft may have specific render targets bound depending on what rendering phase is active. The `GuiGraphics` object provided to `render()` has its own buffer management.

The design document doesn't mention this at all. It will cause the Compose UI to be invisible or render to the wrong target.

**Recommendation:** `BlitPass.blit()` must accept a target framebuffer ID parameter. In the NeoForge integration, this should be obtained from `Minecraft.getMainRenderTarget().frameBufferId` (or from `glGetIntegerv(GL_FRAMEBUFFER_BINDING)` before any state changes). This is a design issue in the core abstraction — the blit pass interface needs to be framebuffer-target-aware.

---

### C4. `PlatformContext.Empty` breaks clipboard, cursor management, and IME

The `ComposeRenderer` uses `PlatformContext.Empty` when creating the scene. This means:

- **No clipboard:** `Ctrl+C`, `Ctrl+V`, `Ctrl+X` will silently do nothing in text fields. The document lists "text fields work (typing, selection, Cmd+A, Cmd+C/V/X)" as a Phase 1 deliverable — this will fail.
- **No cursor changes:** The mouse cursor won't change to a text beam when hovering text fields, or to a pointer over clickable elements.
- **No input method (IME):** CJK text input won't work at all.

The document doesn't mention `PlatformContext` anywhere. For Minecraft integration, you need a `MinecraftPlatformContext` that:
- Implements `ClipboardManager` by delegating to GLFW's `glfwGetClipboardString`/`glfwSetClipboardString`
- Implements `ViewConfiguration` for touch slop, long-press timeout, etc.
- Implements cursor changes via GLFW's `glfwSetCursor` (or via Minecraft's own cursor management if it has one)

**Recommendation:** Add `MinecraftPlatformContext` to Phase 1 deliverables. At minimum, implement clipboard support and cursor management. Without clipboard, text fields are severely broken.

---

### C5. `AbstractContainerScreen` slot interaction is far more complex than described

The document describes `ComposeContainerScreen` (§2.4) as:

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

Option 2 is the only sane approach, but it means the `ComposeContainerScreen` must preserve a significant amount of vanilla's internal state and coordinate between Compose's input handling and the vanilla slot interaction state machine.

Additionally, since `ComposeContainerScreen` overrides `render()` and doesn't call `super.render()`, the ghost item rendering (item following cursor), slot highlight (white semi-transparent overlay on hovered slots), and item count overlays must all be reimplemented or handled through hybrid rendering.

**Recommendation:** Add a dedicated subsection to Phase 2 acknowledging this complexity. The recommended approach is to let the vanilla `AbstractContainerScreen` input methods execute first (handling slot interactions), and only forward non-consumed input events to the Compose scene. The Compose UI should be purely visual (rendering slots, items, backgrounds) while the vanilla code handles the interaction protocol.

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

If a caching approach is attempted, consider:
- **Batching:** Render all items into a single atlas FBO in one pass
- **Caching:** Only re-render items whose `ItemStack` changed (compare via `ItemStack.matches()`)
- **Size:** Each item FBO size (16x16? 32x32? 64x64?) affects total memory

**Recommendation:** Consider a hybrid rendering approach for container screens. Instead of capturing every item into Compose textures, render the Compose UI (backgrounds, labels, buttons) via the Skia pipeline, then overlay Minecraft-native item rendering on top using `GuiGraphics`. The `renderBg` override renders the Compose layer, then the vanilla `renderSlotContents` (or a custom equivalent) renders items natively. This is simpler, faster, and compatible with all modded items.

---

### C7. Escape key handling is unaddressed

The document never mentions how the Escape key is handled. In Minecraft:

- Pressing Escape in a `Screen` calls `Screen.onClose()`, which typically calls `minecraft.setScreen(null)` or returns to the parent screen
- `Screen.keyPressed()` has default handling: if `keyCode == GLFW_KEY_ESCAPE`, it calls `this.onClose()`
- If `ComposeScreen` overrides `keyPressed` to forward to Compose, and Compose doesn't consume the Escape key, the screen should close
- If Compose DOES consume the Escape key (e.g., closing a popup/dropdown within the Compose UI), the screen must NOT close

This interaction between Compose's key event consumption and Minecraft's Escape-to-close behavior needs explicit design. The wrong behavior here will either make screens impossible to close or make it impossible to use Escape for in-UI navigation.

The same issue applies to other MC-reserved keys: F11 (fullscreen), F3 (debug), F2 (screenshot), etc.

**Recommendation:** `ComposeScreen.keyPressed()` should first forward the key event to Compose. If Compose reports the event as consumed (e.g., a dropdown was dismissed), return `true` (event handled, don't close). If Compose didn't consume it and the key is Escape, call `onClose()`. This requires `ComposeScene.sendKeyEvent()` to return a consumed/not-consumed signal — verify that the API supports this. Certain keys (F11, F3, etc.) may need to always be intercepted before reaching Compose.

---

### C8. The threading model description is misleading

The document states in §1.3:

> `MinecraftCoroutineDispatcher` — dispatches Compose work to Minecraft's render thread via `Minecraft.getInstance().tell(Runnable)`.

`Minecraft.tell(Runnable)` dispatches to the **game/tick thread** (`Minecraft` extends `ReentrantBlockableEventLoop<Runnable>`). In NeoForge 1.21.1, the game thread and the render thread are the **same thread** — Minecraft is single-threaded for both ticking and rendering. The document's phrasing "render thread" is technically not wrong (since they're the same thread), but it suggests the author may believe these are separate threads with `tell()` specifically routing to the render thread.

The Technical Decisions table says "Thread safety: Single-threaded (render thread). All Compose work on MC render thread." — this is the correct mental model, but it should acknowledge that `tell()` is a game-loop dispatch, not a render-specific dispatch. The Compose frame clock ticked from `Screen.render()` is what actually ties Compose work to the render phase of the game loop.

If Minecraft ever moves to a multi-threaded rendering model, or if a mod like Sodium/Embeddium introduces off-thread rendering, the assumption breaks.

**Recommendation:** Clarify the threading model explicitly: "Minecraft 1.21.1 is single-threaded — the game thread IS the render thread. `Minecraft.tell()` enqueues work for the next game loop tick. `Screen.render()` is what provides render-phase timing for the Compose frame clock."

---

## Important Concerns

Things that should be addressed in the design but won't necessarily block initial development.

---

### I1. Window resize / `Screen.resize()` lifecycle will destroy Compose state

The document lists `init()` as where `ComposeRenderer` is created. But `init()` is called on initial show AND on every resize (via `resize()` calling `rebuildWidgets()` → `init()`). If `ComposeRenderer` is recreated on every resize, that destroys the entire Compose scene:

- All Compose state (`remember`, `mutableStateOf`) is lost
- Any ongoing animations restart
- Text field content is cleared
- Scroll positions reset

This is catastrophic for user experience during a resize. The `ComposeRenderer.resize()` method exists in the test harness specifically to handle this without recreating the scene.

Beyond basic resize, the following lifecycle edge cases also need handling:

- **F11 fullscreen toggle:** Changes the framebuffer size. `Screen.resize()` is called, but the ComposeRenderer's `resize()` must also be called. If the resize happens between frames, the FBO size and Compose scene size will be out of sync.
- **Window minimize (iconify):** On Windows, `Screen.render()` continues to be called with zero-size framebuffers when minimized. Rendering to a 0x0 FBO will crash OpenGL. The renderer must guard against zero dimensions.
- **Alt-tab:** Input focus is lost — any held keys should be released in Compose's state (synthetic release events for all pressed buttons/keys).
- **GUI scale change at runtime:** Pressing F8 or changing the GUI scale in options changes `guiScale` mid-session. The `ComposeScreen` must detect this and update Compose's `Density`.
- **Singleplayer pause:** When a singleplayer world is paused, the game tick stops but rendering continues. Verify that this doesn't break Compose animations if the frame clock uses `delta` from `Screen.render()`.

**Recommendation:** Override `Screen.resize()` in `ComposeScreen` to call `composeRenderer.resize()` instead of the default `rebuildWidgets()` flow. Do NOT recreate the Compose scene on resize. Add a "Lifecycle Edge Cases" section to Phase 1 that explicitly handles: zero-dimension guard, fullscreen toggle, GUI scale changes, and alt-tab focus loss. Test each scenario.

---

### I2. Multiple Compose surfaces sharing a single `DirectContext` need careful management

The document says (§4.3):

> When a `ComposeHud` and a `ComposeScreen` are active simultaneously, they share the `DirectContext` but have separate FBO/Surface instances.

This is architecturally correct, but the document doesn't address:

- **FBO memory budget:** A 1920x1080 RGBA8 FBO with DEPTH24_STENCIL8 consumes ~12 MB of VRAM. A HUD + screen + overlay means ~36 MB. At 4K, this becomes ~96 MB. With world-space billboards (Phase 7), each entity billboard adds another FBO. Consider whether the HUD could use a smaller FBO (only the region it actually covers, not fullscreen).
- **Skia `DirectContext` state sharing:** When switching between surfaces backed by different FBOs, `context.resetGL()` must be called between each surface's render. The document covers this for the single-surface case but doesn't describe the multi-surface orchestration.
- **Render ordering:** The HUD renders during the `RenderGuiLayerEvent`, the Screen renders during `Screen.render()`, and the Overlay renders during `ScreenEvent.Render.Post`. These are different points in the frame. Each needs its own GL state save/restore cycle. The document should specify the per-surface render sequence explicitly.

**Recommendation:** Design a `ComposeSurfaceManager` that owns the `DirectContext` and coordinates multiple surfaces. It should track total VRAM usage, handle GL state save/restore between surface renders, and provide an API for registering/unregistering surfaces.

---

### I3. `observeMinecraft {}` per-frame polling concerns

The document proposes (§2.3):

> Re-reads the value once per frame via `withFrameNanos`. If the value didn't change, no recomposition.

There are two concerns:

1. **Per-frame allocation:** `withFrameNanos` suspends and resumes every frame, which means the coroutine executes every frame. If the lambda allocates (e.g., creates a list or a data class), this creates per-frame garbage. The implementation should use structural equality checks (`==`) and only update the `MutableState` if the value actually changed.

2. **Equality semantics:** For value types (`Int`, `Float`, `Boolean`), `equals()` works. For reference types (e.g., a `List<ItemStack>`), the default `equals()` may not detect changes if the list is mutated in-place rather than replaced. If many `observeMinecraft {}` calls exist in a single screen (e.g., 50 observers), that's 50 coroutine resumptions per frame.

**Recommendation:** Document that `observeMinecraft {}` uses structural equality for change detection. Provide guidance on what types work well (primitives, data classes, immutable collections) vs. what types are problematic (mutable collections, objects with reference equality). Consider providing an overload with a custom equality comparator. Note that the lambda must be cheap and allocation-free for hot paths.

---

### I4. LWJGL version conflict between Minecraft and Skiko

Minecraft 1.21.1 bundles LWJGL **3.3.3**. The test harness also uses 3.3.3. But Skiko's native library may link against a different LWJGL version internally, or expect specific LWJGL behavior.

Minecraft uses LWJGL's OpenGL bindings (`org.lwjgl.opengl.GL*`), and the test harness uses them directly via `GL33C.*`. In the mod environment, these classes are loaded by Minecraft's classloader. If Skiko also uses LWJGL OpenGL bindings internally (it does, for the OpenGL backend on macOS), there could be classloading conflicts if Skiko expects a different package structure or class version.

The document sets `skiko.macos.opengl.enabled=true` to force Skiko to use OpenGL on macOS instead of Metal. This works in the test harness but in the Minecraft mod environment, Skiko's native library loading path may conflict with how NeoForge's module system handles native libraries.

**Recommendation:** Explicitly test Skiko native library loading in the NeoForge classloader environment early in Phase 1. If Skiko uses `System.loadLibrary()` or `System.load()`, verify that NeoForge's `TransformingClassLoader` doesn't interfere. If it does, you may need to use `--add-opens` JVM flags or a custom library loading strategy.

---

### I5. `ComposeScreen` skipping `super.render()` has downstream consequences

The document says (§1.4):

> `render(GuiGraphics, mouseX, mouseY, delta)` — saves GL state, ticks frame clock, renders Compose, blits FBO, restores GL state. **Does NOT call `super.render()`.**

Not calling `super.render()` skips `Screen.render()`, which in NeoForge 1.21.1:

1. Calls `renderBackground(guiGraphics)` if applicable (for the darkened/dirt background)
2. Calls `this.renderables.forEach { it.render(guiGraphics, ...) }` — renders all vanilla widgets added via `addRenderableWidget()`
3. Handles tooltip rendering for focused narrative elements

If ComposeScreen fully replaces all rendering with Compose, skipping `super.render()` is correct and intentional. However, this means:

- **No vanilla `Renderable` widgets can be mixed in.** This should be explicitly documented as a consequence.
- **No background is rendered.** Phase 1 screens will have no background unless the mod developer handles it themselves. The document mentions `MinecraftBackground` as a component in Phase 3 — this gap should be noted in Phase 1.
- **The narrator system expects `this.renderables`** to contain the selectable widgets. Phase 5's narrator bridge must override `updateNarrationState(NarrationState)` directly instead of relying on the widget iteration in `super.render()`.

**Recommendation:** Verify exactly what `Screen.render()` does in NeoForge 1.21.1 and document each skipped responsibility.

---

### I6. The `runtime-mod` distribution model has unresolved classloading concerns

The document proposes (§3, Runtime Distribution):

> The Compose runtime (Compose UI, Foundation, Skiko native) is shipped as a **standalone library mod** (`runtime-mod`). Other mods that use compose-minecraft declare it as a dependency.

In NeoForge, each mod JAR is loaded by the same `TransformingClassLoader`. This means:

- **If two mods bundle different versions of compose-minecraft,** only one version will be loaded (whichever appears first on the classpath). The `runtime-mod` approach avoids this, but only if ALL compose-minecraft mods correctly declare it as a dependency and DON'T shadow/bundle the runtime themselves.
- **Skiko's native libraries** are loaded via `System.loadLibrary()` or equivalent. Native libraries can only be loaded once per JVM. If the `runtime-mod` loads them, and a mod tries to load them independently, you get `UnsatisfiedLinkError: Native Library already loaded in another classloader`.
- **Compose compiler output** is tightly coupled to the Compose runtime version. If `runtime-mod` pins Compose 1.8.2, all mods MUST be compiled with the matching Compose compiler plugin. Mismatched compiler versions will crash with `NoSuchMethodError` or similar binary incompatibility errors.
- NeoForge uses a layered classloader system. If the `runtime-mod` loads Compose/Skiko classes, and a mod that depends on it tries to use those classes, they must be loaded by the same classloader (or a parent). Forge/NeoForge has historically had issues with library mods that ship their own dependencies — the `JarInJar` system exists for this, but it has quirks. Skiko's native library loading is particularly sensitive to classloader context.

**Recommendation:** The `runtime-mod` should include a version check at load time. When a mod initializes its compose-minecraft integration, verify that the Compose compiler version used to compile the mod matches the runtime version. Provide a clear error message if there's a mismatch. Also document that Skiko native libraries MUST NOT be bundled in individual mod JARs.

---

### I7. The `Screen.render()` parameter is `partialTick`, not `delta`

The document shows `render(GuiGraphics, mouseX, mouseY, delta)` with `delta` as a float. In NeoForge 1.21.1, the signature is `render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)`. The parameter name is `partialTick`, not `delta` — it's the partial tick interpolation factor (0.0 to 1.0), not a delta time. This matters because:
- Using `partialTick` as `nanoTime` for Compose animations would be wrong
- The frame timestamp for Compose should come from `System.nanoTime()` or `Util.getNanos()`, not from the render method parameter

**Recommendation:** Correct the naming in the design document and ensure the Compose frame clock implementation uses `System.nanoTime()`.

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

### I9. Font strategy undermines the "Minecraft-native feel" core principle

The document says:

> Default Skia font for v1. MC bitmap font integration deferred. Skia sans-serif works immediately.

Using a sans-serif system font in a Minecraft UI is not a minor visual mismatch — it fundamentally breaks the "Minecraft-native feel by default" core principle listed at the top of the document. Every component in Phase 3 (`MinecraftButton`, `MinecraftTextField`, etc.) will look wrong with a sans-serif font. The MinecraftTheme becomes a lie.

For a library whose primary value proposition is "UIs that feel native to the game," shipping v1 without the Minecraft font undermines the entire pitch. Mod developers evaluating the library will see non-MC-looking text and dismiss it.

**Recommendation:** Elevate font integration to Phase 1 or early Phase 3. At minimum, load Minecraft's default font texture (`assets/minecraft/textures/font/ascii.png` + `ascii_sga.png`) and create a Skia `Typeface` from it. Alternatively, use Minecraft's bundled TrueType fonts (since 1.20, Minecraft bundles Unicode TTF fonts that can be loaded directly by Skia).

---

### I10. `GuiGraphics` should not be completely hidden

The document treats `GuiGraphics` as something to hide/wrap (§ Types Exposed vs. Wrapped: "Wrap / hide — Highly version-dependent"). But in NeoForge 1.21.1, `GuiGraphics` is the primary API for:

- Drawing text (with shadow, with background, centered, etc.)
- Drawing textures (resource locations mapped to sprites)
- Drawing tooltips (with item context for mod tooltip handlers via NeoForge's `ITooltipExtension` system)
- Scissoring (the built-in `enableScissor`/`disableScissor`)
- Item rendering (`renderItem`, `renderItemDecorations`)
- Pose stack management for 2D transforms

While the Compose rendering pipeline replaces most of these, `ComposeContainerScreen` will need `GuiGraphics` for item rendering, tooltip rendering that integrates with mod-provided tooltip components, and the `renderSlot` callback which mods may override.

The `GuiGraphics` instance is passed to `Screen.render()` and is only valid during that call. It cannot be stored.

**Recommendation:** Don't completely hide `GuiGraphics`. Provide an escape hatch where mod developers can access the current frame's `GuiGraphics` for interop with vanilla/modded rendering. This is especially important for container screens where hybrid rendering (Compose + vanilla items) is the practical approach.

---

### I11. `Screen.isPauseScreen()` behavior should be documented

In singleplayer, Minecraft pauses the game when a `Screen` with `isPauseScreen() == true` is opened. The default for `Screen` is `true`. The design document should decide whether `ComposeScreen` returns `true` or `false` by default, and whether it should be configurable by the mod developer. For settings screens, pausing is expected. For in-game overlays or HUD-like screens, pausing would be wrong.

**Recommendation:** Make `isPauseScreen()` configurable via a constructor parameter with a sensible default (likely `true` to match vanilla behavior).

---

### I12. `NeoForgeGlStateHelper` as a singleton contradicts the "no singletons" decision

Section 1.2 shows `object NeoForgeGlStateHelper` — a Kotlin singleton. The Technical Decisions table says singletons are avoided and shared resources are mod-scoped. The GL state helper is stateful (it saves/restores state) and making it an object means it can't handle re-entrant or nested save/restore calls from multiple Compose surfaces.

**Recommendation:** Make `NeoForgeGlStateHelper` a regular class with instance-based state, or use a stack-based save/restore pattern to support nesting.

---

## Minor Suggestions

Nice-to-haves, style improvements, or things to keep in mind.

---

### M1. `GL_RGBA` vs `GL_RGBA8` in `FramebufferObject`

`FramebufferObject.configureAttachments()` uses unsized `GL_RGBA` as the internal format:

```kotlin
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL)
```

But `ComposeRenderer.createSkiaSurface()` tells Skia the format is `GR_GL_RGBA8` (sized). Most drivers treat unsized `GL_RGBA` with `GL_UNSIGNED_BYTE` as RGBA8, but the spec doesn't guarantee this. Use `GL_RGBA8` explicitly:

```kotlin
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL)
```

---

### M2. GLSL version should be `#version 150 core`, not `#version 330 core`

Minecraft 1.17+ requires OpenGL 3.2 core profile, which supports GLSL 150. The current shaders use GLSL 330 (requires GL 3.3). While almost all GPUs that support GL 3.2 also support 3.3, using `#version 150` would be strictly correct for the minimum supported GL version. The shaders are trivial and use no GLSL 330 features.

---

### M3. The blit pass doesn't restore GL state after drawing

`BlitPass.blit()` sets up GL state (disables scissor/stencil/depth, enables blend, binds shader/VAO/texture) but never restores previous state. In the standalone test harness this is fine (the blit is the last thing before swap). In Minecraft, anything that renders after the blit (tooltips, overlays, debug info, other mods' GUI layers) will inherit the blit's GL state.

The document mentions `NeoForgeGlStateHelper.save()/restore()` wrapping the entire Compose render pass, but the save/restore should happen around the complete pass (including the blit), and the restore must cover everything the blit modifies: blend function, bound program, bound VAO, bound texture, active texture unit.

---

### M4. Consider using `glBlitFramebuffer` instead of a custom quad blit

OpenGL 3.0+ provides `glBlitFramebuffer()` which can copy an FBO's color attachment to another framebuffer without a custom shader/quad. This is simpler (no shader, no VAO, no VBO), potentially hardware-accelerated on some GPUs, and causes less GL state pollution.

The downside is that `glBlitFramebuffer` doesn't support premultiplied alpha blending — it's a raw copy. For overlays that need alpha compositing, the custom quad approach is necessary. But for full-screen `ComposeScreen` that replaces the entire screen content, `glBlitFramebuffer` would be simpler.

---

### M5. Consider named parameters with defaults for `ComposeScreen` configuration

The current API requires passing a lambda to the constructor. Consider whether additional configuration (pause behavior, background rendering, escape handling) warrants additional named parameters with sensible defaults.

---

### M6. Phase ordering: Localization should be much earlier

`translatedString()` is needed by virtually every UI screen. If Phase 3 builds `MinecraftButton` with text, that text should come from lang files. Deferring localization to Phase 5 means all Phase 3 components will use hardcoded English strings during development, and the localization integration will need to be retrofitted.

**Recommendation:** Move `translatedString()` (the simple lang file bridge) to Phase 1 or early Phase 2. The narrator bridge can stay in Phase 5.

---

### M7. The `ComposeHud.register` and `ComposeOverlay.register` APIs use static registration

The document shows:

```kotlin
ComposeHud.register(ResourceLocation("mymod", "custom_hotbar")) { ... }
```

This looks like a static/global registration. The document explicitly says "Singleton pattern: Avoided" in Technical Decisions. These registration APIs should be scoped to the mod's lifecycle (e.g., registered during mod initialization via NeoForge's event bus, unregistered when the mod is unloaded if hot-reloading is supported).

---

### M8. `ContainerState.sync()` using `ItemStack.copy()` on every slot every frame is wasteful

With 63 slots in a chest screen at 60 FPS, that's 3,780 `ItemStack.copy()` calls per second. Each copy allocates a new `ItemStack` object and deep-copies the component data. Most slots don't change between frames.

**Recommendation:** Compare before copying. Use `ItemStack.matches(other)` (which checks item type and components without copying) to detect changes. Only copy when a change is detected. This reduces allocations by ~95% for typical container screens where most slots are static.

---

### M9. `Screen.tick()` should be used for state bridging

`Screen.tick()` is called 20 times per second (once per game tick), separate from `render()` which is called every frame. The Compose frame clock should only be ticked from `render()` — ticking it from `tick()` at 20 TPS would make animations stutter. But `tick()` is the appropriate place to call `sync()` for state bridging, since game state only changes at tick rate.

**Recommendation:** Call `sync()` from `Screen.tick()` (20 TPS) to update state holders, and tick the Compose frame clock from `Screen.render()` (every frame) for smooth animations.

---

### M10. `ResourceLocation` constructor may have changed in 1.21.1

The API examples use `ResourceLocation("mymod", "custom_hotbar")`. In 1.21.1 (NeoForge), the two-argument constructor `ResourceLocation(String, String)` may be deprecated or replaced by `ResourceLocation.fromNamespaceAndPath()` or `ResourceLocation.withDefaultNamespace()`. Verify the correct factory method for 1.21.1.

---

### M11. The `ComposeResources` holder should own more than just `DirectContext`

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

## Questions for the Authors

Things that need clarification or investigation.

---

### Q1. How will the `runtime-mod` handle classloading isolation?

NeoForge uses a layered classloader system. If the `runtime-mod` loads Compose/Skiko classes, and a mod that depends on it tries to use those classes, they must be loaded by the same classloader (or a parent). Has this been verified? Forge/NeoForge has historically had issues with library mods that ship their own dependencies — the `JarInJar` system exists for this, but it has quirks. Skiko's native library loading (`System.loadLibrary` / `System.load`) is particularly sensitive to classloader context.

---

### Q2. What happens when two mods both depend on `compose-minecraft` but expect different versions?

The "strictly pinned" Compose version policy means mod A (built against compose-minecraft 1.0) and mod B (built against compose-minecraft 2.0, with a newer Compose version) can't coexist. This is a real problem in the Minecraft modding ecosystem. How does this interact with NeoForge's dependency resolution? Is semantic versioning sufficient, or does this need a more nuanced compatibility strategy?

---

### Q3. Does Skiko work with Minecraft's bundled LWJGL version?

Minecraft 1.21.1 bundles LWJGL 3.3.3. The test harness also uses 3.3.3. But Skiko may have its own expectations about LWJGL version or native bindings. Has this been tested with the exact LWJGL version Minecraft bundles? Are there any transitive LWJGL dependencies from Skiko that might conflict?

Additionally, Minecraft 1.21.1 requires OpenGL 3.2 core profile. Verify that Skiko doesn't require any extensions beyond what Minecraft's minimum spec provides.

---

### Q4. How will `ComposeResources` (the shared `DirectContext`) be lifecycle-managed?

The document says it's "mod-scoped" and "passed via constructor injection." But:
- When is it created? At mod construction time? At first screen open?
- When is it destroyed? At game shutdown? What if the player quits to the main menu and rejoins?
- What if the GL context is lost (window recreation, fullscreen toggle on some drivers)?
- Who is responsible for calling `close()`?

A `DirectContext` is a heavyweight GPU resource. Its lifecycle management is non-trivial and should be documented.

---

### Q5. Has `CanvasLayersComposeScene` been verified as the correct and stable scene type?

The code uses `CanvasLayersComposeScene`, which is marked as `@InternalComposeUiApi`. This means:
- It can change or be removed without notice between Compose versions
- Its behavior and contract are not documented for external consumers

Is there a public API alternative? `ComposeScene` (the older API) was deprecated in favor of `MultiLayerComposeScene` in some Compose versions. The choice of scene implementation should be documented with rationale and a migration plan if the internal API changes.

---

### Q6. What about Compose's `Popup` and `Dialog` — do they work without a real window?

Several components (MinecraftDropdown, MinecraftTooltip, MinecraftDialog) rely on `Popup` or `Dialog` composables. In Compose Desktop, these create actual OS-level child windows. In the Minecraft context, there's no Compose window — just a canvas. Does `CanvasLayersComposeScene` handle popups as scene layers (which is what the "Layers" in the name suggests), or does it try to create OS windows? This needs verification.

---

### Q7. How will `skiko.macos.opengl.enabled=true` be set in a mod context?

The test harness sets this in `main()` before any Skiko classes load. In a NeoForge mod, mod classes are loaded by the mod classloader during discovery, which may trigger Skiko class loading before any mod code runs. This property might need to be set via JVM args in the launcher profile, which is a poor user experience. Is there a Skiko API to force the OpenGL backend programmatically before initialization?

Has Skiko's OpenGL backend also been tested on Windows? On Windows and Linux, Skiko defaults to OpenGL, but Skiko's OpenGL backend has historically been less tested than Metal (macOS) and Direct3D (Windows).

---

### Q8. How will the library handle multiple Minecraft versions simultaneously?

The document shows `platform/neoforge-1.21.1/` and `platform/neoforge-1.21.4/` as separate modules. But:
- Will both modules exist in the same repository and build simultaneously?
- Will they share the same `core` and `ui` modules?
- How will the Gradle build handle different Minecraft dependency versions?
- Will mod developers need to choose the correct platform module at compile time?

NeoForge's version numbering (1.21.1 vs 1.21.4) implies API differences. The `core` module's interfaces must be designed broadly enough to accommodate both. If a method exists in 1.21.4 but not 1.21.1, the accessor interface must handle this gap.

---

### Q9. What happens when two mods both register a `ComposeHud` that replaces the vanilla hotbar?

The document says ComposeHud "Can optionally cancel vanilla layers it replaces." If mod A and mod B both register a HUD that cancels the vanilla hotbar and provides their own, which one wins? NeoForge's `RegisterGuiLayersEvent` has ordering (`above`/`below` other layers), but two mods canceling the same vanilla layer will conflict.

Is there a conflict resolution strategy, or is this a "don't do that" documentation issue?

---

### Q10. How does the `CanvasLayersComposeScene` behave when the window is not focused?

When Minecraft's window is not focused (alt-tabbed away), `Screen.render()` may still be called (Minecraft continues rendering in the background by default, configurable via "Pause on Lost Focus"). Does `CanvasLayersComposeScene.render()` handle this gracefully? Are there assumptions about GLFW events continuing to arrive?

If the user alt-tabs while holding a key or mouse button, Compose may never receive the release event. This leaves internal state (like a pressed button state) stuck. The document should address how to handle focus loss — potentially by sending synthetic release events for all pressed buttons/keys when focus is lost.

---

### Q11. What is the memory overhead of a `CanvasLayersComposeScene` + `DirectContext`?

The document claims "static HUD costs essentially nothing" but doesn't quantify:
- How much memory does a `DirectContext` consume? (It caches shaders, textures, render targets internally)
- How much memory does a `CanvasLayersComposeScene` consume at rest? (The Compose node tree, layout cache, semantics tree)
- With a HUD + screen + overlay, what's the baseline GPU memory footprint before any content is rendered?

This would help mod developers understand the cost of adopting the library.

---

### Q12. What is the plan for testing the GL state save/restore correctness?

The document lists "no GL state corruption" as a validation criterion but doesn't describe how to verify this. Manual visual inspection is unreliable — subtle state corruption (e.g., wrong blend function on a specific vanilla GUI element) may not be immediately obvious.

**Suggestion:** Create a diagnostic mode that dumps the full GL state (all `glGet*` queries for the state categories the library touches) before save, after save, and after restore. Compare the pre-save and post-restore states programmatically. This can be a dev-only tool, not shipped in production.

---

### Q13. Security: Can `observeMinecraft {}` expose server-side data to the client?

The document's `observeMinecraft {}` escape hatch lets mod developers read arbitrary Minecraft state. In a multiplayer context, the client only has access to data the server has sent. This isn't a vulnerability in compose-minecraft itself (it can't read data the client doesn't have), but the API should document that `observeMinecraft {}` only accesses client-side state and cannot be used to gain information the client shouldn't have.

---

### Q14. Does the design account for Minecraft's `PoseStack` / matrix state during Screen rendering?

When `Screen.render(GuiGraphics, mouseX, mouseY, delta)` is called, `GuiGraphics` has an active `PoseStack` that may have been transformed (scaled by `guiScale`, potentially translated). The Compose rendering pipeline assumes it's rendering to a framebuffer at pixel coordinates. The blit pass uses NDC coordinates (-1 to +1) which bypass the model-view matrix via the custom shader, but after the blit, if Minecraft expects the `PoseStack` to be in a specific state, and the blit pass dirtied any matrix state via `RenderSystem`, there could be issues.

**Recommendation:** Before blitting, save `RenderSystem`'s matrix state. After blitting, restore it. Or verify that the custom shader pipeline is completely independent of Minecraft's matrix stack.
