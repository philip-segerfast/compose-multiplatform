# Design Document Review — Compose for Minecraft

Reviewed against `DESIGN.md` and the test harness implementation.

---

## Critical Issues

### C1. The `core` module cannot have "zero MC imports" as claimed

The architecture diagram (Section 1) and module structure (Section 3) claim `core` has "zero MC imports" and contains the API interfaces. But the document simultaneously says `ItemStack`, `ResourceLocation`, `Component`, and `AbstractContainerMenu` are exposed directly (Types Exposed vs. Wrapped table). The `LocalPlayerAccessor` interface in `core` has `val mainHandItem: ItemStack` — that's a Minecraft import. `ComposeContainerScreen` is generic over `AbstractContainerMenu` — another MC import.

This means `core` cannot be MC-independent. Either:
- Split `core` into `core-render` (truly MC-independent: renderer, FBO, blit, shaders) and `core-api` (depends on MC for type references), or
- Accept that `core` depends on MC (at compile time, against a specific MC version), which undermines the version-isolation goal, or
- Replace all MC types in the API with abstractions (e.g., `ItemStackSnapshot` data class), which adds complexity and loses the "expose stable types directly" benefit.

This is a fundamental tension in the module design that needs resolution before implementation begins.

### C2. The dependency graph is inverted — `core` cannot depend on `platform`

The dependency graph shows `mod-developer's-mod → ui → core → platform/neoforge-X.Y.Z`. But `core` contains the abstract/interface types (`ComposeScreen`, `ComposeContainerScreen`) and `platform` contains the implementations (`NeoForgeComposeScreen`). In a clean dependency graph, `platform` depends on `core` (to implement its interfaces), not the other way around. The arrow `core → platform` implies `core` has a compile-time dependency on the platform module, which defeats the purpose of the abstraction layer.

The correct graph should be:
```
mod-developer's-mod → ui → core ← platform/neoforge-X.Y.Z
                                        │
                                        ▼
                                  NeoForge + Minecraft
```

Where mod code and the platform module both depend on `core`, and the platform module is wired in at runtime (via NeoForge's mod loading, service loaders, or manual DI). This needs to be clarified because it affects how every module is structured.

### C3. `glBindFramebuffer(GL_FRAMEBUFFER, 0)` in `BlitPass` will not work in Minecraft

The blit pass hardcodes framebuffer 0 as the render target. In vanilla Minecraft 1.17+, the game renders to its own managed framebuffer (`MainTarget`), not framebuffer 0. With shader mods (Iris, Optifine, Sodium), additional framebuffers are in play. The blit must target whatever framebuffer Minecraft currently has bound, which means either:
- Querying `glGetIntegerv(GL_FRAMEBUFFER_BINDING)` before Skia renders and restoring it for the blit, or
- Accepting Minecraft's target FBO ID as a parameter to the blit pass.

The design document doesn't mention this at all. It will cause the Compose UI to be invisible or render to the wrong target.

### C4. `PlatformContext.Empty` means no clipboard — copy/paste in text fields won't work

The test harness uses `PlatformContext.Empty`, which provides no clipboard implementation. The design document doesn't mention providing a clipboard implementation for Minecraft. Ctrl+C/V/X in `BasicTextField` will silently do nothing. This is a Phase 1 deliverable ("Text fields work... Cmd+C/V/X") that will fail without a `PlatformContext` that implements clipboard access.

Minecraft provides `GLFW.glfwGetClipboardString()` / `glfwSetClipboardString()` for clipboard access. A custom `PlatformContext` implementation that delegates to these is needed.

### C5. The `NeoForgeGlStateHelper` shown as an `object` (singleton) contradicts the "no singletons" decision

Section 1.2 shows `object NeoForgeGlStateHelper` — a Kotlin singleton. The Technical Decisions table says singletons are avoided and shared resources are mod-scoped. This is a minor contradiction but worth noting since the GL state helper is stateful (it saves/restores state) and making it an object means it can't handle re-entrant or nested save/restore calls.

---

## Important Concerns

### I1. `Minecraft.tell()` dispatches to the game thread, which IS the render thread — but this isn't obvious

The document says `MinecraftCoroutineDispatcher` dispatches "to Minecraft's render thread via `Minecraft.getInstance().tell(Runnable)`." In Minecraft, `tell()` dispatches to the main game thread, which is also the render thread (they're the same thread). This is correct, but the phrasing "render thread" could be misleading if someone assumes there's a separate render thread (like in some game engines). The document should explicitly state: "Minecraft's main thread is both the game tick thread and the render thread. `Minecraft.tell()` enqueues work to this thread."

### I2. Window resize / `Screen.resize()` lifecycle needs explicit handling

The document mentions "Resize the window while the screen is open, verify layout updates" as a validation step, but doesn't describe the implementation. In Minecraft, when the window resizes or GUI scale changes, `Screen.resize(Minecraft, width, height)` is called, which by default calls `Screen.init()` again (rebuilding all widgets). Since `ComposeScreen` bypasses Minecraft's widget system, it needs to:
- Override `resize()` to call `composeRenderer.resize(newPixelWidth, newPixelHeight)`
- Update the Compose scene's density if GUI scale changed
- NOT call `super.resize()` (which would call `init()` and rebuild vanilla widgets that don't exist)

This is a non-obvious lifecycle detail that should be documented in Phase 1.

### I3. The `Screen.render()` parameter is `partialTick`, not `delta`

The document shows `render(GuiGraphics, mouseX, mouseY, delta)` with `delta` as a float. In NeoForge 1.21.1, the signature is `render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)`. The parameter name is `partialTick`, not `delta` — it's the partial tick interpolation factor (0.0 to 1.0), not a delta time. This matters because:
- Using `partialTick` as `nanoTime` for Compose animations would be wrong
- The frame timestamp for Compose should come from `System.nanoTime()` or `Util.getNanos()`, not from the render method parameter

### I4. FBO memory with multiple simultaneous Compose surfaces

Section 4.3 mentions that HUD + Screen + Overlay share a `DirectContext` but have separate FBO/Surface instances. At 1920x1080 with RGBA8, each FBO is ~8 MB. With 3 surfaces, that's ~24 MB of GPU memory just for Compose overlays. The document should note this and consider whether the HUD could use a smaller FBO (only the region it actually covers, not fullscreen).

### I5. Item rendering via FBO capture may be expensive for large inventories

The document decides on "FBO capture via MC ItemRenderer" for rendering items. A chest has 27 slots + 36 player inventory slots = 63 item stacks. If each requires a separate FBO render + texture readback, this could be expensive. Consider:
- Batching: Render all items into a single atlas FBO in one pass
- Caching: Only re-render items whose `ItemStack` changed (hash by item type + count + NBT)
- Size: How big is each item FBO? 16x16? 32x32? 64x64?

The design document should at least acknowledge this performance consideration and sketch the caching strategy.

### I6. `observeMinecraft { }` will execute every frame even if the value hasn't changed

Section 2.3 says `observeMinecraft` re-reads the value once per frame via `withFrameNanos`. The re-reading isn't the problem — the issue is that `withFrameNanos` suspends and resumes every frame, which means the coroutine executes every frame. If the lambda allocates (e.g., creates a list or a data class), this creates per-frame garbage. The implementation should use structural equality checks (`==`) and only update the `MutableState` if the value actually changed, and the document should note that the lambda must be cheap and allocation-free for hot paths.

### I7. `ComposeContainerScreen` must handle ghost item / cursor-held item rendering

When a player picks up an item from a slot (clicks and holds), Minecraft renders a "ghost item" following the cursor. In vanilla, `AbstractContainerScreen` handles this in `renderFloatingItem()`. Since `ComposeContainerScreen` overrides `render()` and doesn't call `super.render()`, this ghost item rendering must be reimplemented in Compose. The design document doesn't mention this at all.

Similarly, the vanilla slot highlight (white semi-transparent overlay on hovered slots) needs to be reimplemented.

### I8. `Screen.isPauseScreen()` behavior should be documented

In singleplayer, Minecraft pauses the game when a `Screen` with `isPauseScreen() == true` is opened. The default for `Screen` is `true`. The design document should decide whether `ComposeScreen` returns `true` or `false` by default, and whether it should be configurable by the mod developer. For settings screens, pausing is expected. For in-game overlays or HUD-like screens, pausing would be wrong.

### I9. Escape key handling needs specification

Minecraft expects `Screen.onClose()` to be called when the player presses Escape. In the current input bridging design, all key events go to Compose. If Compose doesn't consume the Escape key, it should fall through to Minecraft's `Screen.keyPressed()` default handler, which calls `onClose()`. But if the `ComposeScreen` overrides `keyPressed()` to forward to Compose, and Compose doesn't have any special Escape handling, the screen won't close. The design needs to specify how Escape (and potentially other MC-reserved keys like F11, F3, screenshot keys) are handled — either:
- Compose gets first pass; if not consumed, forward to `super.keyPressed()`
- Certain keys are always intercepted before reaching Compose

### I10. `Screen.init()` is called on every resize — re-creating the renderer would destroy Compose state

The document lists `init()` as where `ComposeRenderer` is created. But `init()` is called on initial show AND on every resize (via `resize()` calling `rebuildWidgets()` → `init()`). If `ComposeRenderer` is recreated on every resize, that destroys the entire Compose scene (losing all state). The implementation must either:
- Only create the renderer once (in the constructor or first `init()` call)
- Override `resize()` to prevent `init()` from being re-called

This is a common trap in Minecraft modding.

---

## Minor Suggestions

### M1. GLSL version should be `#version 150 core`, not `#version 330 core`

Minecraft 1.17+ requires OpenGL 3.2 core profile, which supports GLSL 150. The current shaders use GLSL 330 (requires GL 3.3). While almost all GPUs that support GL 3.2 also support 3.3, using `#version 150` would be strictly correct for the minimum supported GL version. The shaders are trivial and use no GLSL 330 features.

### M2. The `GL_RGBA` internal format in `FramebufferObject` should be `GL_RGBA8`

The FBO texture uses `GL_RGBA` as the internal format, but `ComposeRenderer` creates the Skia `BackendRenderTarget` with `GR_GL_RGBA8`. While most drivers treat unsized `GL_RGBA` as RGBA8, explicitly using `GL_RGBA8` is more correct and avoids potential mismatches on strict drivers.

### M3. Consider providing named parameters with defaults for `ComposeScreen` configuration

The current API requires passing a lambda to the constructor. Consider whether additional configuration (pause behavior, background rendering, escape handling) warrants additional named parameters with sensible defaults.

### M4. Phase 1 screens will have no background unless the mod developer handles it

Vanilla screens call `renderBackground(guiGraphics)` to draw the dirt texture or darkened overlay. Since `ComposeScreen` doesn't call `super.render()`, it won't get a background. The document mentions `MinecraftBackground` as a component in Phase 3, but Phase 1 screens will have no background unless the mod developer handles it themselves. This should be noted in Phase 1.

### M5. `ResourceLocation` constructor may have changed in 1.21.1

The API examples use `ResourceLocation("mymod", "custom_hotbar")`. In 1.21.1 (NeoForge), the two-argument constructor `ResourceLocation(String, String)` may be deprecated or replaced by `ResourceLocation.fromNamespaceAndPath()` or `ResourceLocation.withDefaultNamespace()`. Verify the correct factory method for 1.21.1.

### M6. The document doesn't mention how to handle `Screen.tick()`

`Screen.tick()` is called 20 times per second (once per game tick), independent of framerate. Some mods use this for periodic updates. The design should decide whether `ComposeScreen` exposes a tick callback, or whether `observeMinecraft {}` (per-frame) is sufficient for all use cases.

---

## Questions for the Authors

### Q1. How will the `runtime-mod` handle classloading isolation?

NeoForge uses a layered classloader system. If the `runtime-mod` loads Compose/Skiko classes, and a mod that depends on it tries to use those classes, they must be loaded by the same classloader (or a parent). Has this been verified? Forge/NeoForge has historically had issues with library mods that ship their own dependencies — the `JarInJar` system exists for this, but it has quirks. Skiko's native library loading (`System.loadLibrary` / `System.load`) is particularly sensitive to classloader context.

### Q2. What happens when two mods both depend on `compose-minecraft` but expect different versions?

The "strictly pinned" Compose version policy means mod A (built against compose-minecraft 1.0) and mod B (built against compose-minecraft 2.0, with a newer Compose version) can't coexist. This is a real problem in the Minecraft modding ecosystem. How does this interact with NeoForge's dependency resolution? Is semantic versioning sufficient, or does this need a more nuanced compatibility strategy?

### Q3. Does Skiko work with Minecraft's bundled LWJGL version?

Minecraft 1.21.1 bundles LWJGL 3.3.3. The test harness also uses 3.3.3. But Skiko may have its own expectations about LWJGL version or native bindings. Has this been tested with the exact LWJGL version Minecraft bundles? Are there any transitive LWJGL dependencies from Skiko that might conflict?

### Q4. How will `ComposeResources` (the shared `DirectContext`) be lifecycle-managed?

The document says it's "mod-scoped" and "passed via constructor injection." But:
- When is it created? At mod construction time? At first screen open?
- When is it destroyed? At game shutdown? What if the player quits to the main menu and rejoins?
- What if the GL context is lost (window recreation, fullscreen toggle on some drivers)?
- Who is responsible for calling `close()`?

A `DirectContext` is a heavyweight GPU resource. Its lifecycle management is non-trivial and should be documented.

### Q5. Has the `CanvasLayersComposeScene` API been verified as stable/public?

The test harness uses `CanvasLayersComposeScene` from `androidx.compose.ui.scene`. This is a relatively new API in Compose Multiplatform. Is it marked as stable, or is it experimental/internal? If it's internal API, it could change without notice in future Compose versions, which would be painful given the "strictly pinned" version policy.

### Q6. What about Compose's `Popup` and `Dialog` — do they work without a real window?

Several components (MinecraftDropdown, MinecraftTooltip, MinecraftDialog) rely on `Popup` or `Dialog` composables. In Compose Desktop, these create actual OS-level child windows. In the Minecraft context, there's no Compose window — just a canvas. Does `CanvasLayersComposeScene` handle popups as scene layers (which is what the "Layers" in the name suggests), or does it try to create OS windows? This needs verification.

### Q7. How will `skiko.macos.opengl.enabled=true` be set in a mod context?

The test harness sets this in `main()` before any Skiko classes load. In a NeoForge mod, mod classes are loaded by the mod classloader during discovery, which may trigger Skiko class loading before any mod code runs. This property might need to be set via JVM args in the launcher profile, which is a poor user experience. Is there a Skiko API to force the OpenGL backend programmatically before initialization?
