package window

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.unit.Density
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import render.ComposeRenderer
import kotlin.system.exitProcess

/**
 * A GLFW window that hosts a Compose UI rendered via Skia into an OpenGL FBO.
 *
 * ## Usage
 * ```kotlin
 * GlfwWindow(width = 800, height = 600, title = "My App").run {
 *     MyComposeContent()
 * }
 * ```
 *
 * [run] blocks on the GLFW event loop until the window is closed, then cleans
 * up all resources and calls [exitProcess].
 *
 * ## Architecture
 *
 * ```
 *   GlfwWindow (GLFW lifecycle, callbacks, event loop)
 *       └── ComposeRenderer (Skia context, Compose scene, FBO, blit pass)
 *              └── FramebufferObject + BlitPass + ShaderProgram
 * ```
 *
 * GlfwWindow owns the platform window and delegates all rendering to
 * [ComposeRenderer]. Input events are bridged to Compose via [subscribeToGLFWEvents].
 */
@OptIn(InternalComposeUiApi::class)
class GlfwWindow(
    private var width: Int,
    private var height: Int,
    private val title: String,
) {
    private var windowHandle: Long = 0
    private var density: Density = Density(1f)

    /**
     * Initializes the GLFW window and OpenGL context, creates the Compose
     * renderer, sets the given composable as content, and enters the event loop.
     *
     * This method blocks until the window is closed. After the loop exits,
     * all resources are released and [exitProcess] is called.
     */
    fun run(content: @Composable () -> Unit) {
        // -- Initialize GLFW --
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "Failed to initialize GLFW" }

        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_SRGB_CAPABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)

        windowHandle = glfwCreateWindow(width, height, title, NULL, NULL)
        check(windowHandle != NULL) { "Failed to create GLFW window" }

        glfwMakeContextCurrent(windowHandle)
        glfwSwapInterval(1) // V-sync

        // -- Initialize OpenGL --
        GL.createCapabilities()
        glEnable(GL_FRAMEBUFFER_SRGB)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        // -- Query actual framebuffer size and display scale --
        queryFramebufferSize()
        queryContentScale()
        glViewport(0, 0, width, height)

        // -- Create the Compose renderer --
        val renderer = ComposeRenderer(width, height, density)
        val dispatcher = GlfwCoroutineDispatcher()

        // Magenta background to verify transparency.
        // In the real Minecraft mod, the game world would already be rendered
        // to the default framebuffer, and we'd leave clearColor as null.
        // Any area of the Compose UI that is transparent will show this color.
        val backgroundClearColor = floatArrayOf(0.8f, 0.2f, 0.6f, 1f)

        val frameDispatcher = renderer.createScene(
            coroutineContext = dispatcher,
            clearColor = backgroundClearColor,
            onFrameRendered = {
                glfwSwapBuffers(windowHandle)
            },
        )

        // -- Wire up GLFW callbacks --
        glfwSetWindowCloseCallback(windowHandle) { dispatcher.stop() }

        glfwSetWindowContentScaleCallback(windowHandle) { _, xscale, _ ->
            density = Density(xscale)
            renderer.density = density
            frameDispatcher.scheduleFrame()
        }

        glfwSetFramebufferSizeCallback(windowHandle) { _, fbWidth, fbHeight ->
            width = fbWidth
            height = fbHeight
            renderer.resize(fbWidth, fbHeight)

            // Render immediately during resize to avoid visual lag.
            // Temporarily disable V-sync so the render isn't throttled.
            glfwSwapInterval(0)
            renderer.render(clearColor = backgroundClearColor)
            glfwSwapBuffers(windowHandle)
            glfwSwapInterval(1)
        }

        // -- Connect GLFW input events to the Compose scene --
        renderer.scene.subscribeToGLFWEvents(windowHandle) { density.density }

        // -- Set content and show the window --
        renderer.setContent(content)
        glfwShowWindow(windowHandle)

        // -- Enter the event loop (blocks until window is closed) --
        dispatcher.runLoop()

        // -- Cleanup --
        renderer.close()
        glfwDestroyWindow(windowHandle)
        glfwTerminate()
        exitProcess(0)
    }

    /**
     * Queries the actual framebuffer size (in pixels, accounting for HiDPI).
     */
    private fun queryFramebufferSize() {
        MemoryStack.stackPush().use { stack ->
            val w = stack.mallocInt(1)
            val h = stack.mallocInt(1)
            glfwGetFramebufferSize(windowHandle, w, h)
            width = w.get()
            height = h.get()
        }
    }

    /**
     * Queries the display content scale and updates [density].
     */
    private fun queryContentScale() {
        MemoryStack.stackPush().use { stack ->
            val xScale = stack.mallocFloat(1)
            val yScale = stack.mallocFloat(1)
            glfwGetWindowContentScale(windowHandle, xScale, yScale)
            density = Density(xScale.get())
        }
    }
}
