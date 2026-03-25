package render

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.*
import org.jetbrains.skia.FramebufferFormat.Companion.GR_GL_RGBA8
import org.jetbrains.skiko.FrameDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Manages the full Compose → Skia → OpenGL rendering pipeline.
 *
 * ## Architecture
 *
 * ```
 *   Compose UI  ──→  Skia (DirectContext)  ──→  FBO texture  ──→  BlitPass  ──→  screen
 *                         renders into              sampled by
 * ```
 *
 * The pipeline has four steps, executed in [render]:
 *
 *  1. **Reset Skia's GL state cache** — tell Skia which GL state categories were
 *     modified by the blit pass since the last Skia render, so it doesn't use
 *     stale cached state.
 *
 *  2. **Render Compose via Skia into the FBO** — Skia binds our FBO (via
 *     [Surface.makeFromBackendRenderTarget]), traverses the Compose node tree,
 *     and issues GL draw commands.
 *
 *  3. **Flush Skia's command buffer** — non-blocking flush (no CPU-GPU sync).
 *     GL guarantees command ordering within a context, so the FBO texture will
 *     be fully written by the time our blit executes.
 *
 *  4. **Blit the FBO texture to the screen** — [BlitPass] restores the GL state
 *     that Skia dirtied, then draws a fullscreen textured quad onto the default
 *     framebuffer.
 *
 * ## Dirty-flag optimization
 *
 * The Compose scene's `invalidate` callback sets [sceneDirty] to `true`.
 * [render] atomically consumes this flag — when `false`, it skips steps 1–3
 * and goes straight to the blit pass, reusing the FBO texture from the last
 * render. This is critical for the Minecraft mod use case where the game runs
 * at 60+ FPS but the UI overlay may be static.
 *
 * ## Lifecycle
 *
 *  1. Construct with initial dimensions and density
 *  2. Call [createScene] to create the Compose scene (requires a coroutine context)
 *  3. Call [render] each frame
 *  4. Call [resize] when the window dimensions change
 *  5. Call [close] to release all resources
 */
@OptIn(InternalComposeUiApi::class)
class ComposeRenderer(
    private var width: Int,
    private var height: Int,
    density: Density,
) {
    // -- GL resources --
    private val fbo = FramebufferObject()
    private val blitPass = BlitPass()

    // -- Skia --
    private val context: DirectContext = DirectContext.makeGL()
    private var surface: Surface = createSkiaSurface(width, height, createFbo = true)

    // -- Compose scene --
    private lateinit var _scene: ComposeScene
    val scene: ComposeScene get() = _scene

    // -- Dirty flag --
    /** Set to true by Compose's invalidate callback when the scene tree needs a re-render. */
    private val sceneDirty = AtomicBoolean(true)

    var density: Density = density
        set(value) {
            field = value
            if (::_scene.isInitialized) {
                _scene.density = value
            }
        }

    /**
     * Creates the [CanvasLayersComposeScene] and the [FrameDispatcher] that
     * drives frame scheduling.
     *
     * @param coroutineContext the dispatcher on which Compose will run
     *        (typically a [window.GlfwCoroutineDispatcher])
     * @param onFrameRendered called after each [render] completes, typically
     *        used to swap buffers (e.g. `glfwSwapBuffers`)
     * @return a [FrameDispatcher] that should be used to schedule frames
     */
    fun createScene(
        coroutineContext: CoroutineContext,
        onFrameRendered: () -> Unit = {},
    ): FrameDispatcher {
        val frameDispatcher = FrameDispatcher(coroutineContext) {
            render()
            onFrameRendered()
        }

        _scene = CanvasLayersComposeScene(
            density = density,
            size = IntSize(width, height),
            coroutineContext = coroutineContext,
            invalidate = {
                sceneDirty.set(true)
                frameDispatcher.scheduleFrame()
            },
            platformContext = PlatformContext.Empty,
        )

        return frameDispatcher
    }

    /**
     * Sets the Compose UI content. Must be called after [createScene].
     */
    fun setContent(content: @Composable () -> Unit) {
        _scene.setContent(content)
    }

    /**
     * Executes one frame of the rendering pipeline.
     *
     * If the Compose scene hasn't been invalidated since the last render,
     * only the blit pass executes (reusing the cached FBO texture).
     */
    fun render() {
        val needsSceneRender = sceneDirty.getAndSet(false)

        if (needsSceneRender) {
            // Step 1: Tell Skia which GL state categories were modified by the
            // blit pass since the last Skia render. Without this, Skia uses
            // stale cached state and may render to the wrong target or with
            // incorrect settings.
            context.resetGL(
                GLBackendState.RENDER_TARGET,   // glBindFramebuffer
                GLBackendState.VIEW,            // glViewport, glDisable(GL_SCISSOR_TEST)
                GLBackendState.BLEND,           // glEnable(GL_BLEND), glBlendFunc
                GLBackendState.VERTEX,          // glBindVertexArray
                GLBackendState.STENCIL,         // glDisable(GL_STENCIL_TEST)
                GLBackendState.PROGRAM,         // glUseProgram
                GLBackendState.TEXTURE_BINDING, // glBindTexture, glActiveTexture
                GLBackendState.MISC,            // glDisable(GL_DEPTH_TEST)
            )

            // Step 2: Render Compose UI via Skia into the FBO.
            // The Surface is bound to our FBO via makeFromBackendRenderTarget —
            // Skia will bind the correct FBO itself.
            surface.canvas.clear(Color.WHITE)
            _scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())

            // Step 3: Flush Skia's GPU command buffer without stalling the CPU.
            // GL guarantees command ordering within a context, so the FBO
            // texture will be fully written by the time the blit executes.
            context.flush(surface)
            context.submit(false)
        }

        // Step 4: Blit the FBO texture to the default framebuffer.
        blitPass.blit(fbo.colorTextureId, width, height)
    }

    /**
     * Handles a window resize. Recreates the FBO and Skia surface at the new
     * dimensions, and forces a full re-render on the next [render] call.
     */
    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight

        // Close the old surface and flush any pending Skia commands
        surface.close()
        context.flush()

        // Recreate the FBO storage at the new size
        fbo.resize(newWidth, newHeight)

        // Recreate the Skia surface bound to the resized FBO
        surface = createSkiaSurface(newWidth, newHeight, createFbo = false)

        // Update the Compose scene's layout size
        if (::_scene.isInitialized) {
            _scene.size = IntSize(newWidth, newHeight)
        }

        // Force a full re-render since the FBO contents are now undefined
        sceneDirty.set(true)
    }

    /**
     * Releases all GPU resources and closes the Compose scene.
     */
    fun close() {
        if (::_scene.isInitialized) {
            _scene.close()
        }
        surface.close()
        context.flush()
        blitPass.delete()
        fbo.delete()
        context.close()
    }

    /**
     * Creates a Skia [Surface] backed by our [FramebufferObject].
     *
     * @param createFbo if true, creates the FBO (first-time init). If false,
     *        assumes the FBO already exists (resize path).
     */
    private fun createSkiaSurface(width: Int, height: Int, createFbo: Boolean): Surface {
        if (createFbo) {
            fbo.create(width, height)
        }

        val renderTarget = BackendRenderTarget.makeGL(
            width, height,
            /* sampleCount = */ 0,
            /* stencilBits = */ 8,
            fbo.fboId,
            GR_GL_RGBA8,
        )

        return Surface.makeFromBackendRenderTarget(
            context, renderTarget, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        ) ?: error("Failed to create Skia surface from FBO (${width}x${height})")
    }
}
