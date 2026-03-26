# Compose for Minecraft — Design Document

## Vision

A library that brings Jetpack Compose's declarative UI model to Minecraft modding. Mod developers write composable functions to build UIs — screens, HUD overlays, inventories — and the library handles rendering them into Minecraft's OpenGL context via Skia.

**Primary target:** NeoForge 1.21.1 (Fabric support planned later).

**Core principles:**
- Minecraft-native feel by default, full creative freedom when desired
- Version isolation — mod developers bump the library version instead of rewriting UI code when Minecraft updates
- Fast iteration via Compose Desktop playground with hot reload
- Clean architecture encouraged but not enforced

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Rendering Pipeline](#2-rendering-pipeline)
3. [Module Structure](#3-module-structure)
4. [Phase 1 — Core Rendering + ComposeScreen](#phase-1--core-rendering--composescreen)
5. [Phase 2 — Container Screens + State Bridging](#phase-2--container-screens--state-bridging)
6. [Phase 3 — Minecraft UI Component Library](#phase-3--minecraft-ui-component-library)
7. [Phase 4 — HUD + Overlay](#phase-4--hud--overlay)
8. [Phase 5 — Narrator](#phase-5--narrator)
9. [Phase 6 — Desktop Playground](#phase-6--desktop-playground)
10. [Phase 7 — World-Space Rendering](#phase-7--world-space-rendering)
11. [Technical Decisions](#technical-decisions)
12. [Open Questions](#open-questions)

---

## 1. Architecture Overview

```
┌────────────────────────────────────────────────────────────────┐
│                     Mod Developer Code                         │
│  ┌─────────────────┐  ┌──────────────┐  ┌─────────────────┐    │
│  │ Composable UIs  │  │  State/Logic │  │  Lang Files     │    │
│  │ (common module) │  │  (common)    │  │  (assets/lang/) │    │
│  └────────┬────────┘  └──────┬───────┘  └───────┬─────────┘    │
│           │                  │                   │             │
├───────────┼──────────────────┼───────────────────┼─────────────┤
│           ▼                  ▼                   ▼             │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                compose-minecraft-ui                     │   │
│  │  MinecraftTheme, MinecraftButton, MinecraftSlider,      │   │
│  │  ItemSlot, SlotGrid, PlayerInventory, ...               │   │
│  └─────────────────┬─────────────────────┬─────────────────┘   │
│                    │                     │                     │
│  ┌─────────────────▼───────────────┐  ┌──▼────────────────────┐│
│  │       compose-minecraft-core    │  │  core-api             ││
│  │       (zero MC imports)         │  │  (MC type refs only)  ││
│  │                                 │  │                       ││
│  │  Rendering (MC-independent):    │  │  Interfaces:          ││
│  │    ComposeRenderer, BlitPass,   │  │    LocalPlayerAccessor││
│  │    FramebufferObject,           │  │    ContainerAccessor  ││
│  │    ShaderProgram,               │  │    WorldAccessor      ││
│  │    GlfwKeyMapping               │  │                       ││
│  │                                 │  │  Types:               ││
│  │  Platform abstractions:         │  │    ContainerState     ││
│  │    PlatformContext,             │  │    LocalPlayerState   ││
│  │    InputBridge                  │  │                       ││
│  └─────────────────────────────────┘  └──────────┬────────────┘│
│                                                  │             │
│  ┌───────────────────────────────────────────────▼──────────┐  │
│  │           platform-neoforge-1.21.1                       │  │
│  │  NeoForgeComposeScreen (extends MC Screen),              │  │
│  │  NeoForgeComposeContainerScreen,                         │  │
│  │  NeoForgeLocalPlayerAccessor, NeoForgeGlStateHelper,     │  │
│  │  NeoForgeInputBridge, NeoForgeHudLayer,                  │  │
│  │  NeoForgeNarratorBridge, NeoForgeTextBridge,             │  │
│  │  MinecraftPlatformContext                                │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  Compose UI + Foundation (transitive dependency)               │
│  Skia / Skiko (native rendering)                               │
│  LWJGL OpenGL (provided by Minecraft)                          │
└────────────────────────────────────────────────────────────────┘
```

### Key Architectural Split

The library is split into two core modules:

- **`core`** — Truly MC-independent. Contains the rendering pipeline (ComposeRenderer, BlitPass, FBO, shaders), input abstractions, and platform-agnostic utilities. This module has zero Minecraft imports and can be used by the test harness and the desktop playground without any Minecraft dependency.

- **`core-api`** — Contains interfaces and state holders that reference stable MC types (`ItemStack`, `Component`, `ResourceLocation`, `AbstractContainerMenu`). These types are exposed directly because they are fundamental, stable, and mod developers work with them everywhere. The accessor *interfaces* live here; the *implementations* live in platform modules.

The platform module (`platform/neoforge-1.21.1/`) depends on both `core` and `core-api`, and provides the MC-version-specific implementations.

---

## 2. Rendering Pipeline

The rendering pipeline is proven and working in the test harness (`experimental/lwjgl-integration/`).

```
Compose UI  ──>  Skia (DirectContext)  ──>  FBO texture  ──>  BlitPass  ──>  target framebuffer
                      renders into              sampled by
```

### Pipeline Steps (per frame)

1. **Check dirty flag** — If the Compose scene hasn't been invalidated, skip steps 2-4 and reuse the cached FBO texture. This is critical for performance in the Minecraft use case where the UI overlay may be static while the game runs at 60+ FPS.

2. **Reset Skia's GL state cache** — Call `context.resetGL(...)` with the GL state categories modified by the blit pass (and by Minecraft's own rendering). Skia caches GL state internally; without this reset it uses stale state.

3. **Render Compose via Skia into the FBO** — Clear the FBO with transparent, call `scene.render(canvas, nanoTime)`. Skia binds the FBO, traverses the Compose node tree, issues GL draw commands.

4. **Flush Skia** — Non-blocking flush (`context.flush(surface)` + `context.submit(false)`). GL guarantees command ordering within a context, so the FBO texture is fully written by the time the blit executes.

5. **Blit the FBO texture to the target framebuffer** — Restore GL state that Skia dirtied (scissor, stencil, depth, blend), optionally clear the background, draw a fullscreen textured quad with premultiplied alpha blending (`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`). The target framebuffer ID is passed as a parameter — in Minecraft this is `Minecraft.getMainRenderTarget().frameBufferId`, NOT framebuffer 0.

### Key Rendering Knowledge

- **Skia uses premultiplied alpha.** A red pixel at 50% opacity is stored as `(0.5, 0.0, 0.0, 0.5)`, not `(1.0, 0.0, 0.0, 0.5)`. The blit blend function MUST be `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`.

- **OpenGL is a global state machine.** Both Minecraft's renderer and Skia issue GL commands through the same state. Before Skia renders, we reset its state cache. After Skia renders, we restore the state Minecraft expects.

- **Never manually bind/clear Skia's FBO.** Use `surface.canvas.clear(...)` instead.

- **The blit target is NOT always framebuffer 0.** Since Minecraft 1.17, the game renders to a managed `RenderTarget`. With shader mods (Iris, Sodium/Embeddium), additional framebuffers are in play. The blit pass must accept a target framebuffer ID parameter.

- **The dirty-flag optimization** means static UI costs essentially nothing — one textured quad draw per frame. This is cheaper than vanilla Minecraft's HUD, which re-renders every element every frame with zero caching.

---

## 3. Module Structure

The library lives in its own standalone repository, separate from the compose-multiplatform fork used for the test harness. The test harness remains at `experimental/lwjgl-integration/` for standalone rendering development/testing.

```
compose-minecraft/                 # Separate repository
├── core/                          # MC-independent rendering + platform abstractions
│   └── src/main/kotlin/
│       ├── render/
│       │   ├── ComposeRenderer.kt
│       │   ├── BlitPass.kt
│       │   ├── FramebufferObject.kt
│       │   └── ShaderProgram.kt
│       ├── input/
│       │   ├── KeyMapping.kt            # GLFW → AWT key code mapping
│       │   └── InputBridge.kt           # Interface
│       └── platform/
│           └── PlatformContext.kt       # Clipboard, cursor, view configuration
│
├── core-api/                      # MC type references in interfaces/state holders
│   └── src/main/kotlin/
│       ├── screen/
│       │   ├── ComposeScreen.kt          # Abstract/interface
│       │   └── ComposeContainerScreen.kt # Abstract/interface
│       ├── hud/
│       │   └── ComposeHud.kt            # Abstract/interface
│       ├── overlay/
│       │   └── ComposeOverlay.kt        # Abstract/interface
│       ├── state/
│       │   ├── LocalPlayerAccessor.kt   # Interface
│       │   ├── LocalPlayerState.kt      # Compose state holder
│       │   ├── ContainerAccessor.kt     # Interface
│       │   ├── ContainerState.kt        # Compose state holder
│       │   └── WorldAccessor.kt         # Interface
│       └── text/
│           └── TextBridge.kt            # Component ↔ AnnotatedString conversion interface
│
├── ui/                            # MC-styled Compose components (optional, recommended)
│   └── src/main/kotlin/
│       ├── theme/
│       │   ├── MinecraftTheme.kt
│       │   ├── MinecraftColors.kt
│       │   ├── MinecraftTypography.kt
│       │   └── MinecraftTextures.kt
│       ├── components/
│       │   ├── MinecraftButton.kt
│       │   ├── MinecraftTextField.kt
│       │   ├── MinecraftSlider.kt
│       │   ├── MinecraftCheckbox.kt
│       │   ├── MinecraftDropdown.kt
│       │   ├── MinecraftTooltip.kt
│       │   ├── MinecraftScrollableList.kt
│       │   ├── MinecraftTab.kt
│       │   └── MinecraftWindow.kt       # Bordered container
│       └── inventory/
│           ├── ItemSlot.kt
│           ├── SlotGrid.kt
│           └── PlayerInventory.kt
│
├── platform/
│   ├── neoforge-1.21.1/              # Thin MC-version-specific adapter
│   │   └── src/main/kotlin/
│   │       ├── NeoForgeComposeScreen.kt
│   │       ├── NeoForgeComposeContainerScreen.kt
│   │       ├── NeoForgeLocalPlayerAccessor.kt
│   │       ├── NeoForgeContainerAccessor.kt
│   │       ├── NeoForgeGlStateHelper.kt
│   │       ├── NeoForgeInputBridge.kt
│   │       ├── NeoForgeHudLayer.kt
│   │       ├── NeoForgeNarratorBridge.kt
│   │       ├── NeoForgeTextBridge.kt
│   │       └── MinecraftPlatformContext.kt
│   │
│   └── neoforge-1.21.4/              # Future version
│       └── ...
│
├── playground/                    # Compose Desktop fakes for fast iteration (future)
│   └── src/main/kotlin/
│       ├── PlaygroundPlayerAccessor.kt
│       ├── PlaygroundContainerAccessor.kt
│       ├── PlaygroundPlatformContext.kt
│       ├── DevControlsPanel.kt
│       └── main.kt
│
├── runtime-mod/                   # Standalone mod that ships Compose runtime
│                                  # (other mods declare it as a dependency,
│                                  #  so Compose is loaded once, not per-mod)
│
└── test-harness/                  # Current standalone LWJGL app (dev/testing)
    └── (current experimental/lwjgl-integration code)
```

### Why Two Core Modules

- **`core`** has zero Minecraft imports. It contains the rendering pipeline, shader utilities, and platform abstractions. It is directly usable by the `test-harness` and `playground` modules without any Minecraft dependency.

- **`core-api`** references stable MC types (`ItemStack`, `ResourceLocation`, `Component`, `AbstractContainerMenu`) in its interface signatures. The accessor *interfaces* define the contract; the *implementations* live in platform modules. This is where version isolation happens — when Mojang renames player fields, only the `NeoForgeLocalPlayerAccessor` changes, not the interface.

~70% of the codebase is MC-version-independent (rendering pipeline, Compose components, theme). Using modules avoids duplicating this across branches. Bug fixes in `core` or `ui` benefit all supported versions automatically. Each `platform/*` module is small (~500-800 lines) containing only the thin adapter layer.

### Dependency Graph

```
test-harness ──> core (+ LWJGL/GLFW for standalone window)

playground ──> core + ui (Compose Desktop, fake accessors)

                    ┌── ui ──> core-api ──> core
mod-developer's-mod ┤
                    └── platform/neoforge-X.Y.Z ──> core-api ──> core
                            │
                            ▼
                      NeoForge + Minecraft
```

The mod developer depends on both `ui` (or `core-api` directly) and the appropriate `platform` module. The platform module is wired in at runtime via NeoForge's mod loading.

### Runtime Distribution

The Compose runtime (Compose UI, Foundation, Skiko native) is shipped as a **standalone library mod** (`runtime-mod`). Other mods that use compose-minecraft declare it as a dependency. This avoids bundling ~15 MB of Compose/Skia into every mod jar.

---

## Phase 1 — Core Rendering + ComposeScreen

**Goal:** Open a full-screen Compose UI inside Minecraft. Non-inventory screens only (settings, info panels, custom UIs).

**Depends on:** Nothing (first phase).

### What Gets Built

#### 1.1 Extract Rendering Pipeline from Test Harness

Move the proven rendering code into the `core` module:
- `ComposeRenderer` — Skia context, surface, FBO, dirty flag, render pipeline
- `BlitPass` — Fullscreen quad blit with premultiplied alpha, accepts target framebuffer ID parameter. In the test harness, target is framebuffer 0. In Minecraft, target is `Minecraft.getMainRenderTarget().frameBufferId`.
- `FramebufferObject` — FBO creation, resize, delete. Uses `GL_RGBA8` (sized format) for the color attachment.
- `ShaderProgram` — Shader compilation utility
- Blit shaders (`ui_quad.vert`, `ui_quad.frag`) — Use `#version 150 core` (GLSL 150, compatible with MC's minimum GL 3.2 core profile)
- `GlfwKeyMapping` — GLFW-to-AWT key code mapping (MC uses GLFW internally)

The test harness remains as-is for standalone development/testing.

#### 1.2 GL State Save/Restore

`GlStateHelper` — saves and restores the GL state that Minecraft expects around our Compose render pass. Instance-based with stack save/restore to support nesting (e.g., HUD + Screen active simultaneously). Not a singleton.

Minecraft's `RenderSystem` tracks some state (blend, depth, color mask, shader, textures) which we can query and restore. Raw GL state not tracked by `RenderSystem` is queried via `glGetIntegerv` / `glGetBooleanv`.

```kotlin
class GlStateHelper {
    fun save() { /* push: query RenderSystem + raw GL state */ }
    fun restore() { /* pop: put it all back */ }
}
```

The save/restore wraps the **entire** Compose render pass including the blit. After `restore()`, all GL state that Minecraft cares about is back to its pre-render values.

#### 1.3 Coroutine Dispatcher + Frame Clock

`MinecraftCoroutineDispatcher` — dispatches Compose work to Minecraft's main thread via `Minecraft.getInstance().tell(Runnable)`.

**Threading model:** Minecraft 1.21.1 is single-threaded — the game thread IS the render thread. `Minecraft.tell()` enqueues work for the next game loop iteration. `Screen.render()` is called during the render phase of the game loop. The Compose frame clock is ticked from `Screen.render()`, which is what provides render-phase timing for Compose animations.

`MinecraftFrameClock` — A `MonotonicFrameClock` implementation ticked from inside `Screen.render()` using `System.nanoTime()` (NOT the `partialTick` parameter, which is a 0.0-1.0 interpolation factor). This is required for Compose's animation APIs (`animate*AsState`, `AnimatedVisibility`, `Animatable.animateTo`, etc.) which internally call `withFrameNanos`.

#### 1.4 MinecraftPlatformContext

A `PlatformContext` implementation for the Minecraft environment. Without this, clipboard (Ctrl+C/V/X), cursor changes, and IME would not work.

```kotlin
class MinecraftPlatformContext : PlatformContext {
    // Clipboard via GLFW
    override val clipboardManager = object : ClipboardManager {
        override fun getText() = glfwGetClipboardString(windowHandle)?.let { AnnotatedString(it) }
        override fun setText(text: AnnotatedString) { glfwSetClipboardString(windowHandle, text.text) }
    }

    // Cursor changes via GLFW
    override val viewConfiguration = ...
}
```

#### 1.5 ComposeScreen

A `Screen` subclass that hosts a full-screen Compose UI.

```kotlin
// Mod developer API
Minecraft.getInstance().setScreen(ComposeScreen(
    title = Component.literal("Settings"),
    parent = currentScreen,
    pauseGame = true,  // configurable, default true (matches vanilla)
) {
    MySettingsUI(onClose = { minecraft.setScreen(parent) })
})
```

Implementation details:
- **Constructor** — creates `ComposeRenderer` (once, not recreated on resize). The renderer's lifecycle is tied to the screen instance, not to `init()`.
- `init()` — only called for initial setup. Does NOT create the renderer (since `init()` is re-called on every resize via `rebuildWidgets()`).
- `resize()` — overridden to call `composeRenderer.resize()` and update Compose `Density` to match the new `guiScale`. Does NOT call `super.resize()` (which would trigger `init()` and destroy Compose state).
- `render(GuiGraphics, mouseX, mouseY, partialTick)` — saves GL state, ticks frame clock with `System.nanoTime()`, renders Compose, blits FBO to `Minecraft.getMainRenderTarget().frameBufferId`, restores GL state. Does NOT call `super.render()`.
- `removed()` — disposes Compose scene and GL resources.
- `onClose()` — returns to parent screen.
- `isPauseScreen()` — returns the `pauseGame` constructor parameter (default `true`).

**Skipping `super.render()` has these consequences:**
- No vanilla `Renderable` widgets can be mixed in (this is intentional — ComposeScreen fully replaces all rendering with Compose)
- No background is rendered by default. Mod developers should use `Modifier.background()` or a future `MinecraftBackground` composable. Phase 1 screens will have no dirt texture background unless the mod developer handles it.
- The narrator system cannot iterate `this.renderables`. The Phase 5 narrator bridge must override `updateNarrationState()` directly.

#### 1.6 Input Bridging

Adapt the proven `GlfwEvents.kt` input bridging to work through Minecraft's `Screen` methods instead of raw GLFW callbacks:

| Minecraft Screen Method | Compose Scene Method | Notes |
|---|---|---|
| `mouseClicked(x, y, button)` | `sendPointerEvent(Press)` | Multiply coords by `guiScale` |
| `mouseReleased(x, y, button)` | `sendPointerEvent(Release)` | |
| `mouseDragged(x, y, button, dx, dy)` | `sendPointerEvent(Move)` | |
| `mouseMoved(x, y)` | `sendPointerEvent(Move)` | Hover/cursor tracking |
| `mouseScrolled(x, y, hAmt, vAmt)` | `sendPointerEvent(Scroll)` | Use `preciseWheelRotation` for smooth feel |
| `keyPressed(keyCode, scanCode, mods)` | `sendKeyEvent(KeyDown)` | GLFW keycodes — same mapping |
| `keyReleased(keyCode, scanCode, mods)` | `sendKeyEvent(KeyUp)` | |
| `charTyped(char, mods)` | `sendKeyEvent(KeyTyped)` | Character input |

Critical: modifier booleans (`isCtrlPressed`, `isMetaPressed`, `isAltPressed`, `isShiftPressed`) must be passed explicitly to Compose's `KeyEvent()` constructor. Compose reads these from the event object, not from the AWT native event.

#### 1.7 Key Event Routing (Escape + Reserved Keys)

`ComposeScreen.keyPressed()` uses a **Compose-first, fallback-to-Screen** strategy:

1. Forward the key event to `scene.sendKeyEvent()`
2. If Compose reports the event as **consumed** (e.g., a dropdown was dismissed, a text field handled the key), return `true` — the event is handled, don't propagate.
3. If Compose did **not** consume the event:
   - If the key is **Escape**, call `onClose()` (close the screen)
   - Otherwise, call `super.keyPressed()` to let Minecraft handle it (F11 fullscreen, F3 debug, F2 screenshot, etc.)

This means Escape can serve double duty: dismissing Compose popups/dropdowns when they're open, and closing the screen when nothing else consumes it.

#### 1.8 Screen Lifecycle Edge Cases

The following edge cases must be handled in `ComposeScreen`:

- **Window resize / GUI scale change:** Override `resize()` to call `composeRenderer.resize()` and update `Density`. Do NOT recreate the Compose scene — all `remember`/`mutableStateOf` state, animations, scroll positions, and text field content must survive resize.
- **Zero-dimension guard:** When the window is minimized (Windows), `render()` may be called with zero-width framebuffers. Guard against `resize(0, 0)` — skip rendering if either dimension is 0.
- **Focus loss (alt-tab):** When the window loses focus, send synthetic release events for all pressed keys/buttons to Compose. Otherwise, held-key states get stuck.
- **F11 fullscreen toggle:** Triggers a resize. Handled by the resize override.
- **Singleplayer pause:** When `isPauseScreen() == true` and the world is singleplayer, game ticks stop but rendering continues. Compose animations should still run smoothly since the frame clock uses `System.nanoTime()`, not game tick time.

#### 1.9 Shared Resource Holder

A mod-scoped resource holder (not a static singleton) that owns shared GL and Compose resources. Creating multiple `DirectContext` instances is expensive. Passed to `ComposeScreen` via constructor injection or a mod-level holder.

```kotlin
class ComposeResources {
    val directContext: DirectContext = DirectContext.makeGL()
    val blitPass: BlitPass = BlitPass(...)  // shared shader program + VAO
    val platformContext: MinecraftPlatformContext = MinecraftPlatformContext(...)

    fun close() {
        blitPass.delete()
        directContext.close()
    }
}
```

Lifecycle:
- Created lazily on first use (first screen open), on the render thread after the GL context is available.
- Destroyed at game shutdown via NeoForge's lifecycle events.
- If the GL context is lost (extremely rare — window recreation on some drivers), the `DirectContext` must be recreated. Detect via `context.isAbandoned()`.

#### 1.10 Localization Bridge (Simple)

Moved from Phase 5. `translatedString()` is trivial to implement and needed by virtually every UI screen.

```kotlin
// Simple: returns plain String from MC's lang system
@Composable
fun translatedString(key: String, vararg args: Any): String {
    return Component.translatable(key, *args).getString()
}
```

The rich `Component.toAnnotatedString()` conversion (preserving bold, color, italic, etc.) stays in Phase 5 since it requires more work.

#### 1.11 Font Investigation

Investigate whether Minecraft 1.21.1 bundles TTF/OTF Unicode fonts that Skia can load directly. Since MC 1.20, Minecraft includes bundled TTF fonts for Unicode rendering. If these can be loaded by Skia's `Typeface.makeFromFile()` or `Typeface.makeFromData()`, MC-native text rendering becomes low-effort.

If trivial to integrate, include MC font support in Phase 1. If not, defer to Phase 3 with the understanding that Phase 1-2 screens will use Skia's default sans-serif font (functional but not MC-native looking).

### Deliverables

- A NeoForge mod that can open a Compose screen with interactive UI
- Text fields work (typing, selection, Cmd+A, Cmd+C/V/X, word navigation) via `MinecraftPlatformContext`
- Mouse interaction works (click, hover, drag, scroll)
- Animations work (animate*AsState, AnimatedVisibility)
- GL state is properly saved/restored (no visual corruption of game world)
- Escape key properly closes the screen (or dismisses Compose popups first)
- `translatedString()` reads from MC lang files
- `isPauseScreen` is configurable

### Validation

- Open a Compose screen from the pause menu or a keybind
- Type in text fields, verify modifier shortcuts work (including Ctrl+C/V/X clipboard)
- Scroll lists, verify smooth trackpad feel
- Press Escape — verify screen closes (or popup dismisses first)
- Close the screen, verify the game world renders correctly (no GL state corruption)
- Resize the window while the screen is open, verify layout updates and state is preserved
- Minimize the window, restore it — verify no crash from zero-dimension FBO
- Alt-tab away and back — verify no stuck key states
- Toggle fullscreen (F11) while screen is open — verify it handles the resize
- Test at different GUI scales (1x, 2x, 3x, Auto)
- Use `translatedString()` and verify it reads from lang files
- Test with rendering mods installed (Embeddium at minimum) — verify no visual corruption

---

## Phase 2 — Container Screens + State Bridging

**Goal:** Support inventory/container screens, bridge Minecraft state into Compose reactively.

**Depends on:** Phase 1.

### What Gets Built

#### 2.1 State Accessor Interfaces

Stable API interfaces that abstract volatile Minecraft internals. These live in `core-api` and reference MC types directly.

```kotlin
// In core-api
interface LocalPlayerAccessor {
    val health: Float
    val maxHealth: Float
    val foodLevel: Int
    val saturation: Float
    val experienceLevel: Int
    val experienceProgress: Float
    val isCreative: Boolean
    val isSpectator: Boolean
    val selectedSlot: Int
    val mainHandItem: ItemStack  // MC type — stable enough to expose
    val armorItems: List<ItemStack>
    val statusEffects: List<StatusEffectInfo>
}
```

Implementation in `platform/neoforge-1.21.1/` delegates to the real `LocalPlayer`. When Mojang renames fields, only the implementation changes.

#### 2.2 Compose State Holders

Wrappers that convert accessor values into Compose-observable `mutableStateOf`:

```kotlin
@Stable
class LocalPlayerState internal constructor(
    private val accessor: LocalPlayerAccessor
) {
    var health by mutableFloatStateOf(accessor.health)
        private set
    var foodLevel by mutableIntStateOf(accessor.foodLevel)
        private set

    internal fun sync() {
        health = accessor.health
        foodLevel = accessor.foodLevel
    }
}
```

**Sync timing:** `sync()` is called from `Screen.tick()` (20 TPS), not from `render()`. Game state only changes at tick rate, so syncing every render frame wastes work. The Compose frame clock is still ticked from `render()` for smooth animations. This separation means: state updates at game-tick rate (20 Hz), animations interpolate at render rate (60+ Hz).

Only changed values trigger recomposition — if health changed but food didn't, only health-reading composables recompose.

#### 2.3 Universal State Observer

Escape hatch for any Minecraft state not covered by pre-built accessors:

```kotlin
@Composable
fun <T> observeMinecraft(read: () -> T): State<T>
```

Re-reads the value once per frame via `withFrameNanos`. If the value didn't change (checked via structural equality `==`), no recomposition occurs.

**Usage guidance:**
- Works well with: primitives (`Int`, `Float`, `Boolean`), data classes, immutable collections
- Problematic with: mutable collections mutated in-place (use `toList()` to snapshot), objects with reference-only equality
- The `read` lambda is called every frame — it must be cheap and allocation-free
- For custom equality, use the overload: `observeMinecraft(equality = ...) { ... }`

#### 2.4 ComposeContainerScreen

A `ComposeScreen` variant that wraps an `AbstractContainerMenu`. Uses a **hybrid rendering approach**: vanilla handles slot interaction logic, Compose handles visual rendering.

```kotlin
// Mod developer API
class MyMachineScreen(menu: MyMachineMenu, inv: Inventory, title: Component)
    : ComposeContainerScreen<MyMachineMenu>(menu, inv, title, content = { menu ->
        MyMachineUI(menu)
    })
```

#### Slot Interaction Complexity

`AbstractContainerScreen` has ~800 lines of slot interaction logic that handles:
- Quick-craft/drag-to-distribute (multi-phase state machine with left/right/middle button producing different distribution algorithms)
- Double-click collection of matching items
- Shift-click (`quickMoveStack()` varies per container type)
- Number key swap (1-9 for hotbar slots)
- Drop key (Q for single, Ctrl+Q for full stack)
- Creative mode middle-click clone
- Off-hand swap (F key)

All of this calls `AbstractContainerMenu.clicked(int slotId, int button, ClickType clickType)` with specific `ClickType` values (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`).

**Approach:** The vanilla `AbstractContainerScreen` input methods (`mouseClicked`, `mouseReleased`, `mouseDragged`, `keyPressed`) handle all slot interaction. These execute first. If the input was consumed by slot logic, it doesn't reach Compose. If it wasn't (e.g., clicking outside any slot), it's forwarded to the Compose scene for custom UI elements (buttons, tabs, etc.).

The Compose UI is purely visual: rendering slot backgrounds, item icons, labels, progress bars, and custom UI elements. The vanilla code handles the interaction protocol.

#### Ghost Item + Slot Highlight

Since `ComposeContainerScreen` overrides `render()` and doesn't call `super.render()`, the following must be reimplemented:
- **Carried item rendering** — the item stack following the cursor when the player picks up an item
- **Slot highlight** — white semi-transparent overlay on the hovered slot

These are rendered via `GuiGraphics` after the Compose blit, using the `LocalGuiGraphics` CompositionLocal (see 2.7).

#### 2.5 ContainerState

```kotlin
@Stable
class ContainerState<T : AbstractContainerMenu>(val menu: T) {
    private val slotStates: List<MutableState<ItemStack>>

    fun getSlotItem(index: Int): ItemStack
    internal fun sync() {
        for (i in slotStates.indices) {
            val current = menu.getSlot(i).item
            if (!ItemStack.matches(slotStates[i].value, current)) {
                slotStates[i].value = current.copy()
            }
        }
    }
}
```

**Optimization:** `sync()` uses `ItemStack.matches()` for comparison before calling `ItemStack.copy()`. Only changed slots trigger a copy and recomposition. For a typical chest screen (63 slots), ~95% of slots don't change between ticks, reducing allocations from 63 copies/frame to ~1-3 copies/frame.

#### 2.6 Item Rendering

Items are rendered via FBO capture using MC's `ItemRenderer`. This works for all items including modded ones with custom model renderers, enchantment glint, damage bars, and count overlays.

**Performance considerations for container screens:**
- **Batching:** Render all visible items into a single atlas FBO in one pass, not individual FBOs per item
- **Caching:** Only re-render items whose `ItemStack` changed (detected by `ContainerState.sync()`)
- **Atlas size:** Use a texture atlas (e.g., 256x256) with 16x16 or 32x32 slots per item

#### 2.7 GuiGraphics Escape Hatch

`ComposeContainerScreen` provides access to the current frame's `GuiGraphics` via a `CompositionLocal`:

```kotlin
val LocalGuiGraphics = compositionLocalOf<GuiGraphics> {
    error("No GuiGraphics available outside of ComposeContainerScreen.render()")
}
```

This allows:
- Item rendering via `guiGraphics.renderItem()`
- Tooltip rendering that integrates with mod-provided tooltip components
- Custom vanilla-interop rendering for advanced use cases

The `GuiGraphics` instance is only valid during the current `render()` call and must not be stored.

### Deliverables

- Container screens (chests, furnaces, custom machines) rendered via Compose
- Reactive state bridging with granular recomposition (sync at tick rate)
- `observeMinecraft {}` escape hatch for arbitrary state with equality semantics
- All vanilla slot interactions working (shift-click, stack splitting, drag-to-distribute, number keys, drop, etc.)
- Ghost item and slot highlight rendering
- `LocalGuiGraphics` escape hatch for vanilla rendering interop

### Validation

- Open a Compose-rendered chest screen, verify items display correctly
- Shift-click items between player inventory and chest
- Split stacks, drag-to-distribute (left-click drag = even split, right-click drag = one each)
- Double-click to collect matching items
- Number keys to swap hotbar slots
- Q to drop, Ctrl+Q to drop stack
- Open a custom machine screen with progress bars driven by `ContainerData`
- Verify no item duplication bugs (server-authoritative validation)
- Verify ghost item follows cursor when picking up items
- Verify slot highlight on hover

---

## Phase 3 — Minecraft UI Component Library

**Goal:** A library of Compose components styled to match Minecraft's visual language. Mod developers use these to build UIs that feel native to the game.

**Depends on:** Phase 1. (Phase 2 for inventory-related components.)

### What Gets Built

#### 3.1 MinecraftTheme

```kotlin
MinecraftTheme {
    // All components inside use MC styling
    MinecraftButton(onClick = { }) { Text("Click Me") }
}
```

Uses Compose's `CompositionLocal` system to provide:
- `LocalMinecraftColors` — button colors, text colors, backgrounds
- `LocalMinecraftTypography` — MC font as a Compose `FontFamily` (TTF if available, fallback to Skia default)
- `LocalMinecraftTextures` — references to MC's widget texture atlas

#### 3.2 Components

All built on **Compose Foundation** (not Material 3, not Compose Unstyled):

| Component | Foundation primitives used |
|---|---|
| `MinecraftButton` | `Modifier.clickable` + `Modifier.drawBehind` (MC button texture) |
| `MinecraftTextField` | `BasicTextField` + MC styling |
| `MinecraftSlider` | `Modifier.draggable` + MC slider texture |
| `MinecraftCheckbox` | `Modifier.clickable` + checkbox texture |
| `MinecraftDropdown` | `Popup` + `LazyColumn` + MC styling |
| `MinecraftTooltip` | `Popup` + MC tooltip background (dark purple border) |
| `MinecraftScrollableList` | `LazyColumn` + MC scrollbar texture |
| `MinecraftTab` / `MinecraftTabRow` | `Modifier.selectable` + tab textures |
| `MinecraftWindow` | `Box` with MC bordered container texture |
| `MinecraftBackground` | Dirt texture or darkened overlay |

#### 3.3 Inventory Components

| Component | Description |
|---|---|
| `ItemSlot` | Single inventory slot — renders item icon, count, durability bar |
| `SlotGrid` | Grid of `ItemSlot`s bound to a `ContainerState` |
| `PlayerInventory` | Standard 3x9 + hotbar layout |
| `ItemRenderer` | Composable that renders an `ItemStack` (icon, count overlay, enchantment glint) |

#### 3.4 No Material 3 Dependency

Material 3 is not bundled, not recommended, not blocked. If a mod developer wants it, they add the dependency themselves and manage the jar size.

The Minecraft components are ~30-80 lines each because MC's UI is simple: flat textures, no elevation, no ripple, no rounded corners, bitmap font.

### Deliverables

- `MinecraftTheme` with colors, typography, textures
- ~10 styled components covering common UI patterns
- Inventory components (ItemSlot, SlotGrid, PlayerInventory)
- All components include correct `Modifier.semantics` for future narrator support

### Validation

- Build a settings screen using only library components — verify it looks native to MC
- Build an inventory screen — verify items render correctly
- Test at different GUI scales (1x, 2x, 3x, Auto)
- Compare visual fidelity against vanilla MC screens

---

## Phase 4 — HUD + Overlay

**Goal:** Support persistent UI layers during gameplay (HUD) and on top of open screens (overlays).

**Depends on:** Phase 1, Phase 2 (for state bridging).

### What Gets Built

#### 4.1 ComposeHud

A persistent Compose surface that renders as a Minecraft GUI layer during gameplay.

```kotlin
// Mod developer API — registered during mod initialization via NeoForge event bus
@SubscribeEvent
fun onRegisterGuiLayers(event: RegisterGuiLayersEvent) {
    ComposeHud.register(event, ResourceLocation.fromNamespaceAndPath("mymod", "custom_hotbar")) {
        MyCustomHotbar(
            selectedSlot = playerState.selectedSlot,
            items = playerState.hotbarItems,
        )
    }
}
```

Implementation:
- Registers a NeoForge GUI layer via `RegisterGuiLayersEvent`
- Can optionally cancel vanilla layers it replaces (e.g., the default hotbar)
- Owns a `ComposeRenderer` that persists across frames
- On each frame, renders the Compose scene and blits the result
- Dirty-flag optimization is critical here — HUD is static most frames
- No input handling (player controls the character, not the HUD)

**Performance:** More efficient than vanilla's HUD for static content. Vanilla re-renders every HUD element every frame with zero caching. Our approach: nothing changed → blit one cached texture.

**Memory:** Consider using a smaller FBO sized to the HUD region rather than fullscreen. A hotbar-only HUD doesn't need a 1920x1080 FBO.

#### 4.2 ComposeOverlay

A Compose surface that renders on top of whatever `Screen` is currently open. For JEI-like use cases.

```kotlin
// Mod developer API — registered during mod initialization
@SubscribeEvent
fun onRegisterGuiLayers(event: RegisterGuiLayersEvent) {
    ComposeOverlay.register(event, ResourceLocation.fromNamespaceAndPath("mymod", "recipe_browser")) {
        RecipeBrowserOverlay(
            visible = isOverlayVisible,
            onItemClicked = { showRecipes(it) },
        )
    }
}
```

Implementation:
- Hooks into NeoForge's `ScreenEvent.Render.Post` to blit after the current screen renders
- Intercepts input events via `ScreenEvent.MouseButtonPressed.Pre` etc.
- If Compose consumes the input event, cancels it (prevents it from reaching the underlying screen)
- Show/hide based on which screens are open

#### 4.3 Multiple Compose Surface Management

When a `ComposeHud` and a `ComposeScreen` are active simultaneously, they share the `DirectContext` (via `ComposeResources`) but have separate FBO/Surface instances. Each surface has its own `GlStateHelper` instance for save/restore. The HUD just blits its cached texture (no Skia work if nothing changed).

**Render ordering:** The surfaces render at different points in the frame:
- HUD renders during `RenderGuiLayerEvent`
- Screen renders during `Screen.render()`
- Overlay renders during `ScreenEvent.Render.Post`

Each render point has its own GL state save/restore cycle. Between surface renders, `context.resetGL()` must be called.

**Memory budget:** At 1920x1080, each fullscreen RGBA8 FBO with DEPTH24_STENCIL8 is ~12 MB VRAM. With 3 fullscreen surfaces, that's ~36 MB. At 4K, ~96 MB. HUD surfaces should use region-sized FBOs to reduce this.

### Deliverables

- Custom HUD layers that replace or augment vanilla HUD elements
- Screen overlays that render on top of open screens with input interception
- Performance validation — static HUD costs less than vanilla

### Validation

- Replace the hotbar with a Compose-rendered version — verify it renders during gameplay
- Open inventory while custom HUD is active — verify both render correctly
- Build a JEI-like overlay — verify it renders on top of screens
- Verify overlay input interception (clicking overlay doesn't click through to screen)
- Measure VRAM usage with multiple surfaces active

---

## Phase 5 — Narrator

**Goal:** Integrate with Minecraft's narrator system and provide the rich `Component.toAnnotatedString()` conversion.

**Depends on:** Phase 1 (basic localization already done), Phase 3 (narrator needs components with semantics).

### What Gets Built

#### 5.1 Rich Text Conversion

Converts MC `Component` to Compose `AnnotatedString` with full style preservation:

```kotlin
@Composable
fun Component.toAnnotatedString(): AnnotatedString
```

Style mapping:

| Minecraft Style | Compose AnnotatedString |
|---|---|
| `withBold(true)` | `SpanStyle(fontWeight = FontWeight.Bold)` |
| `withItalic(true)` | `SpanStyle(fontStyle = FontStyle.Italic)` |
| `withColor(0xFF0000)` | `SpanStyle(color = Color(0xFFFF0000))` |
| `withUnderlined(true)` | `SpanStyle(textDecoration = TextDecoration.Underline)` |
| `withStrikethrough(true)` | `SpanStyle(textDecoration = TextDecoration.LineThrough)` |

#### 5.2 Narrator Bridge

Bridges Compose's semantics tree into Minecraft's narration system.

Minecraft's narrator reads from `Screen.selectables` list, calling `appendNarrations(NarrationMessageBuilder)` on hovered/focused elements. Since `ComposeScreen` bypasses Minecraft's widget system, we need a bridge.

Implementation:
- Override `updateNarrationState()` in `ComposeScreen` (since `super.render()` is not called, the narrator can't iterate `this.renderables`)
- On each narration tick, traverse the Compose semantics tree
- Find the focused/hovered semantic node
- Map it to a synthetic `Selectable` registered with the Screen's `selectables` list
- Map `contentDescription` → `NarrationPart.TITLE`, tooltips → `NarrationPart.HINT`, etc.

This is a selling point: mod developers get narrator support for free from `Modifier.semantics`, whereas vanilla requires manual `appendClickableNarrations` implementations.

### Deliverables

- `Component.toAnnotatedString()` with full style preservation
- Narrator reads Compose UI elements when narrator mode is active

### Validation

- Enable narrator, hover over Compose buttons/fields, verify they're narrated
- Test with `Component.translatable("key", arg1)` with format arguments and styles
- Use `translatedString()` (from Phase 1) in a Compose screen, switch languages, verify text updates

---

## Phase 6 — Desktop Playground

**Goal:** A Compose Desktop environment for rapid UI iteration with hot reload, without running Minecraft.

**Depends on:** Phase 1, Phase 2, Phase 3.

### What Gets Built

#### 6.1 Playground Library

A library module that provides fake implementations of all accessor interfaces. Depends on `core` (zero MC imports) for rendering and platform abstractions. Provides its own `PlaygroundPlatformContext`.

```kotlin
class PlaygroundPlayerAccessor(
    health: Float = 20f,
    maxHealth: Float = 20f,
    foodLevel: Int = 20,
) : LocalPlayerAccessor {
    override var health by mutableFloatStateOf(health)
    override var maxHealth by mutableFloatStateOf(maxHealth)
    override var foodLevel by mutableIntStateOf(foodLevel)
}
```

#### 6.2 Dev Controls Panel

A side panel with sliders, dropdowns, and inputs to adjust fake state:
- Player state (health, food, XP, game mode)
- Inventory slots (drag-and-drop fake items)
- GUI scale selector
- Language selector (reads from mod's actual lang files)
- Narrator text output (displayed as text since no MC TTS)
- Window resize for responsive testing

#### 6.3 Texture Loading

Load MC textures from the user's installed Minecraft (auto-detect `.minecraft` directory or accept a configurable path). This gives the playground accurate visual fidelity without bundling Mojang's assets.

#### 6.4 Mod Developer Usage

```kotlin
// Mod developer's playground/build.gradle.kts
dependencies {
    implementation(project(":common"))
    implementation("com.yourlib:compose-minecraft-playground:1.0.0")
}

// Mod developer's playground/src/main/kotlin/main.kt
fun main() = application {
    Window(title = "My Mod Playground") {
        MinecraftTheme {
            MyModSettingsScreen(
                viewModel = FakeSettingsViewModel()
            )
        }
    }
}
```

**Critical constraint:** The `common` module (shared composable code) must have **zero Minecraft imports**. The `core` module's MC-independence makes this possible — the playground depends on `core` (for rendering) and provides fake implementations of `core-api` interfaces.

### Deliverables

- Playground library with fake accessors and dev controls
- Mod developers can iterate on UIs with Compose Hot Reload
- Accurate MC visual fidelity via texture loading from installed MC

### Validation

- Create a sample mod with common/mod/playground split
- Verify hot reload works (edit composable → see change instantly)
- Verify fake state adjustments update the UI reactively
- Compare playground rendering against in-game rendering

---

## Phase 7 — World-Space Rendering

**Goal:** Render Compose surfaces in 3D world space — billboards above entities, block face UIs, waypoint markers.

**Depends on:** Phase 1, Phase 4.

### What Gets Built

#### 7.1 WorldBlitPass

A new blit pass that draws the FBO texture onto a 3D quad in world space instead of a fullscreen 2D quad. Applies camera rotation for billboarding (always faces player).

#### 7.2 ComposeEntityBillboard

Compose surface billboarded above an entity (health bars, name tags, status indicators).

- Hooks into entity rendering pipeline (mixin into `EntityRenderer.render()`)
- Receives `PoseStack`, `MultiBufferSource`, `Camera` from the entity renderer
- Renders Compose into a small FBO, blits onto a billboard quad at the entity's name tag attachment point
- Dirty-flag optimization critical — entity health doesn't change every frame

#### 7.3 ComposeWorldBillboard

Compose surface at a fixed world position facing the camera (waypoint markers, block info overlays).

- Hooks into `RenderLevelStageEvent` (NeoForge)
- Positioned at world coordinates, billboarded toward camera

#### 7.4 ComposeBlockFace

Compose surface rendered on a block face, not billboarded (custom sign content, display screens, in-world monitors).

- Fixed orientation aligned to a block face
- Interactable when player is close and looking at it

### Deliverables

- Entity health bars / name tags rendered via Compose
- Waypoint markers at world positions
- Block-face displays (custom signs, monitors)

### Validation

- Render a Compose health bar above mobs — verify it billboards correctly
- Place a waypoint marker — verify it renders at the correct world position
- Render Compose content on a block face — verify orientation and interaction

---

## Technical Decisions

### Decisions Made

| Decision | Choice | Rationale |
|---|---|---|
| Primary mod loader | NeoForge 1.21.1 | Most feature-rich event system for GUI hooks. Fabric planned later. |
| Rendering approach | Compose → Skia → FBO → blit quad | Proven in test harness. Decouples Compose from MC's renderer. |
| Module split | `core` (MC-free) + `core-api` (MC type refs) | Enables playground + test harness without MC dependency. |
| Component library foundation | Compose Foundation | Unstyled building blocks. No Material 3, no Compose Unstyled. |
| Version management | Modules per version | ~70% of code is MC-independent. Avoids branch duplication. |
| Localization | MC's JSON lang files | Mod ecosystem standard. `translatedString()` in Phase 1. No Compose Resources. |
| State bridging | Accessor interfaces + tick-rate sync | Version isolation via interfaces. Granular recomposition via mutableStateOf. sync() at 20 TPS, frame clock at render rate. |
| Container screen approach | Hybrid: vanilla input, Compose visuals | Vanilla handles ~800 lines of slot interaction. Compose renders UI. |
| Material 3 | Not bundled, not blocked | Mod developers can add it themselves if desired. |
| Frame scheduling | Minecraft-driven + manual frame clock | MC owns the render loop. Frame clock ticked from Screen.render() with System.nanoTime(). |
| Project location | Separate repository | Clean separation from the compose-multiplatform fork used for the test harness. |
| Singleton pattern | Avoided | Shared resources are mod-scoped, passed via injection. GlStateHelper is instance-based with stack save/restore. |
| Runtime distribution | Standalone library mod | Compose/Skia loaded once, shared across mods. |
| Narrator support | Bridge Compose semantics → MC narration | Auto-narrator for all Compose components via updateNarrationState() override. |
| Skiko native distribution | Classifier-based + all-platforms aggregate | Per-platform jars via classifier, plus convenience all-platforms jar. |
| GUI scale handling | guiScale-based Density | Set Compose Density to MC's guiScale. Multiply input coords accordingly. |
| Item rendering | FBO capture via MC ItemRenderer | Works for all items including modded. Batched atlas, cached per-stack. |
| Font strategy | Investigate MC TTF fonts for Phase 1 | If MC 1.21.1 bundles loadable TTFs, integrate early. Fallback: Skia default. |
| Compose version policy | Strictly pinned by runtime-mod | Avoids binary compatibility issues. Mod devs use the pinned version. |
| Thread safety | Single-threaded (game thread = render thread) | All Compose work on MC's single main thread. No locking for v1. |
| Testing strategy | Unit tests + manual testing | Unit tests for pure logic. Manual testing for rendering/interaction. |
| Deep vanilla widget interop | Not planned | Nobody mixes UI toolkits. Full Compose replacement is the pattern. |
| Drag-and-drop | Via vanilla's menu slot protocol | Server-authoritative item movement. Vanilla input methods handle interaction. |
| ViewModels | Deferred | Compose's built-in state management sufficient for v1. Add if requested. |
| Escape key handling | Compose-first, fallback to Screen | Compose gets first pass. If not consumed, Escape calls onClose(). |
| isPauseScreen | Configurable, default true | Constructor parameter. Matches vanilla default. |
| GuiGraphics access | LocalGuiGraphics CompositionLocal | Escape hatch for container screens, item rendering, tooltip interop. |
| PlatformContext | MinecraftPlatformContext | Clipboard via GLFW, cursor changes, view configuration. |
| Blit target | Parameterized framebuffer ID | Not hardcoded to 0. Uses Minecraft.getMainRenderTarget().frameBufferId. |
| GL state helper | Instance-based, stack save/restore | Supports nesting for multiple simultaneous surfaces. |

### Types Exposed vs. Wrapped

| MC Type | Decision | Rationale |
|---|---|---|
| `ItemStack` | Expose directly | Fundamental, stable, mod devs work with it everywhere. |
| `ResourceLocation` | Expose directly | Very stable across versions, used for all identifiers. |
| `Component` | Expose + provide conversion utilities | Stable. `toAnnotatedString()` bridges to Compose. |
| `AbstractContainerMenu` | Expose (type parameter in `ComposeContainerScreen`) | Non-negotiable server-side contract. |
| Player fields (`health`, `foodData`, etc.) | Wrap via `LocalPlayerAccessor` | Volatile — Mojang renames these regularly. |
| `GuiGraphics` | Expose via `LocalGuiGraphics` (container screens) | Needed for item rendering and tooltip interop. Only valid during current render() call. |
| `RenderSystem` GL state | Wrap via `GlStateHelper` | Internal, changes between versions. |

---

## Open Questions

### Resolved

1. ~~**Exact NeoForge version**~~ — **Decided: NeoForge 1.21.1.** Most stable, largest mod ecosystem.

2. ~~**Skiko native distribution**~~ — **Decided: Classifier-based per-platform jars + an aggregated "all-platforms" jar.** Per-platform jars (~8 MB each) are the primary distribution, selected via Maven classifier. An aggregated dependency that includes all platforms (~24 MB) is also published for convenience (users who don't want to think about classifiers). Standard Maven approach.

3. ~~**GUI scale handling**~~ — **Decided: guiScale-based Density.** Set Compose's `Density` to match MC's `guiScale`. Multiply all mouse coordinates by `guiScale` when bridging input from MC's scaled coordinates to Compose's pixel coordinates. Must test at all scale settings (1x, 2x, 3x, Auto).

4. ~~**Item rendering in Compose**~~ — **Decided: FBO capture via MC's ItemRenderer.** Batched into atlas FBO, cached per-stack. Only re-rendered when `ItemStack.matches()` detects a change. Implementation in Phase 2-3.

5. ~~**Minecraft font in Compose**~~ — **Decided: Investigate MC TTF fonts for Phase 1.** MC 1.20+ bundles TTF Unicode fonts. If loadable by Skia, integrate early. If not trivial, defer to Phase 3 with Skia default font as fallback.

6. ~~**Compose version compatibility**~~ — **Decided: Strictly pinned by runtime-mod.** The runtime-mod pins a specific Compose version. Mod developers must use the same version. This avoids binary compatibility issues between Compose compiler output and the runtime. Version upgrades happen when the runtime-mod releases a new version.

7. ~~**Thread safety**~~ — **Decided: Single-threaded assumption for v1.** Minecraft's game thread IS the render thread. All Compose work runs on this thread. `sync()` is called from `Screen.tick()` (game thread). Frame clock ticked from `Screen.render()` (same thread). No locking needed.

8. ~~**Testing strategy**~~ — **Decided: Unit tests + manual testing.** Unit tests for pure logic (accessor mappings, key code mappings, state holder sync, coordinate conversions). Manual testing for rendering and interaction in the actual game. Screenshot comparison tests may be added later using the test harness but are not required for v1.

### Unresolved (Require Investigation)

9. **Classloading isolation** — NeoForge uses a layered classloader system (`TransformingClassLoader`). The `runtime-mod` loads Compose/Skiko classes; mods that depend on it use those classes. Verify that classloading works correctly, particularly for Skiko's native library loading (`System.loadLibrary`/`System.load`) which is sensitive to classloader context. Test with NeoForge's `JarInJar` system.

10. **Multi-mod version conflicts** — If mod A uses compose-minecraft 1.0 and mod B uses 2.0, NeoForge's dependency resolution must handle this. The "strictly pinned" policy means only one version can be loaded. Determine how to communicate this constraint (dependency ranges, clear error messages at startup).

11. **Skiko + Minecraft LWJGL compatibility** — Minecraft 1.21.1 bundles LWJGL 3.3.3. Verify that Skiko's OpenGL backend works with exactly this LWJGL version. Check for transitive LWJGL dependency conflicts. Verify Skiko doesn't require GL extensions beyond MC's minimum GL 3.2 core profile.

12. **`CanvasLayersComposeScene` stability** — The test harness uses `CanvasLayersComposeScene` from `androidx.compose.ui.scene`, which may be marked `@InternalComposeUiApi`. Verify whether this is stable/public API. Determine the migration path if it changes in future Compose versions.

13. **`Popup` and `Dialog` behavior without a real window** — Compose Desktop's `Popup`/`Dialog` create OS-level child windows. In the Minecraft context, there's no Compose window — just a canvas. Verify that `CanvasLayersComposeScene` handles popups as scene layers (which the "Layers" name suggests) rather than attempting to create OS windows. This affects MinecraftDropdown, MinecraftTooltip, and any dialog composables.

14. **`skiko.macos.opengl.enabled=true` in mod context** — The test harness sets this system property in `main()` before Skiko classes load. In a NeoForge mod, Skiko class loading might be triggered during mod discovery, before any mod code runs. Determine if this can be set via a Skiko API rather than a system property, or if it must be in JVM args. Also verify Skiko's OpenGL backend on Windows (Skiko defaults to OpenGL on Windows/Linux, but has historically been less tested than Metal/Direct3D).

15. **Multi-version Gradle build** — The `platform/neoforge-1.21.1/` and `platform/neoforge-1.21.4/` modules must compile against different MC versions. Determine the Gradle configuration strategy for this: separate dependency declarations, version catalogs, or build plugins like VanillaGradle/NeoGradle with per-module configuration.

16. **`DirectContext` lifecycle on GL context loss** — A `DirectContext` is a heavyweight GPU resource. Determine: when is it safe to create (after which point in MC's initialization)? What happens on F11 fullscreen toggle (does the GL context survive)? Does `context.isAbandoned()` reliably detect context loss? Can it be recreated without restarting the game?

17. **Rendering mod compatibility** — Popular rendering mods (Sodium/Embeddium, Iris/Oculus) modify GL state management and framebuffer usage. Determine the testing strategy and any specific GL state categories that need additional save/restore. At minimum, test with Embeddium (the most popular NeoForge rendering mod for 1.21.1).

18. **`ResourceLocation` factory method** — The API examples use `ResourceLocation("mymod", "custom_hotbar")`. In NeoForge 1.21.1, verify whether the two-argument constructor is available or if `ResourceLocation.fromNamespaceAndPath()` is required.

---

## Existing Proven Code

The following code in `experimental/lwjgl-integration/` has been built, tested, and verified working:

| File | Status | Reusable? |
|---|---|---|
| `render/ComposeRenderer.kt` | Working | Yes — core of the library |
| `render/BlitPass.kt` | Working | Yes — needs target FBO parameter, GLSL 150 |
| `render/FramebufferObject.kt` | Working | Yes — needs GL_RGBA8 fix |
| `render/ShaderProgram.kt` | Working | Yes — as-is |
| `resources/assets/shaders/ui_quad.vert` | Working | Yes — needs GLSL 150 |
| `resources/assets/shaders/ui_quad.frag` | Working | Yes — needs GLSL 150 |
| `window/GlfwKeyMapping.kt` | Working | Yes — MC uses same GLFW keycodes |
| `window/GlfwEvents.kt` | Working | Adapt — Screen methods instead of GLFW callbacks |
| `window/GlfwWindow.kt` | Working | Test-harness only |
| `window/GlfwCoroutineDispatcher.kt` | Working | Test-harness only (replace with MC dispatcher) |
| `App.kt` | Working | Test-harness only |
| `main.kt` | Working | Test-harness only |

### Key Bugs Fixed (Must Not Regress)

1. **Compose KeyEvent modifier booleans** — Must pass `isCtrlPressed`, `isMetaPressed`, `isAltPressed`, `isShiftPressed` explicitly to `KeyEvent()` constructor. Compose ignores the AWT native event's modifier bitmask.

2. **Scroll feel** — Must use the 14-parameter `MouseWheelEvent` constructor with `wheelRotation=0` and `preciseWheelRotation=yoffset`. Also `scrollAmount=1` (not 3) since GLFW on macOS provides OS-accelerated deltas.

3. **Premultiplied alpha** — Blit blend function must be `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`, not `GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`.

4. **Transparency flicker** — Background clear must happen atomically inside `BlitPass.blit()`, not in a separate callback before the blit.

5. **Skia GL state reset** — Must call `context.resetGL(...)` before every Skia render with all modified categories.
