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
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.system.exitProcess

fun main() {
    System.setProperty("skiko.macos.opengl.enabled", "true")
    System.setProperty("java.awt.headless", "true")

    Application.start()
}

@OptIn(InternalComposeUiApi::class)
object Application {
    private lateinit var composeFbo: ComposeFBO
    lateinit var composeScene: ComposeScene
    private var windowHandle: Long = 0

    var windowWidth = 640
    var windowHeight = 480
    var density: Density = Density(1f)

    fun start() {
        NodeLogger.group("start()")
        GLFWErrorCallback.createPrint(System.err).set()

        NodeLogger.log("Start - initializing window...")

        glfwInit()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_SRGB_CAPABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)

        windowHandle = glfwCreateWindow(windowWidth, windowHeight, "Compose LWJGL Demo", NULL, NULL)
        glfwMakeContextCurrent(windowHandle)
        glfwSwapInterval(1)

        GL.createCapabilities()
        enable(GL_FRAMEBUFFER_SRGB)
        enable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        NodeLogger.log("Getting initial window size and density...")
        // Set initial density and size
        MemoryStack.stackPush().use { stack ->
            // size
            val width = stack.mallocInt(1)
            val height = stack.mallocInt(1)
            glfwGetFramebufferSize(windowHandle, width, height)
            setSize(width.get(), height.get(), false)
            NodeLogger.log("Initial width = ${this.windowWidth}")
            NodeLogger.log("Initial height = ${this.windowHeight}")

            // density
            val xScale = stack.mallocFloat(1)
            val yScale = stack.mallocFloat(1)
            glfwGetWindowContentScale(windowHandle, xScale, yScale)
            setDensity(xScale.get(), false)
            NodeLogger.log("xScale: $density")
            NodeLogger.log("yScale: ${yScale.get()}")
        }

        NodeLogger.log("Setting initial viewport size (${this.windowWidth}x${this.windowHeight})")
        glViewport(0, 0, windowWidth, windowHeight)

        NodeLogger.log("Creating ShaderManager...")
        val shaderManager = ComposeShaderManager()
        NodeLogger.log("Compiling shaders...")
        shaderManager.compileComposeShaderProgram()
        NodeLogger.log("Create ComposeFBO...")
        composeFbo = ComposeFBO(shaderManager)

        NodeLogger.log("Setting up initial surface...")
        val context = DirectContext.makeGL()
        var surface = createSurface(windowWidth, windowHeight, context) // Skia Surface, bound to the OpenGL framebuffer
        val glfwDispatcher = GlfwCoroutineDispatcher() // a custom coroutine dispatcher, in which Compose will run

        val frameDispatcher = FrameDispatcher(glfwDispatcher) {
            NodeLogger.group("onFrameDispatch")
            render(
                surface = surface,
                context = context,
                shaderProgram = shaderManager.programId,
            )
            NodeLogger.popGroup()
        }

        NodeLogger.log("Creating ComposeScene (Compose Engine)")
        composeScene =
            CanvasLayersComposeScene(
                density = this.density,
                size = IntSize(this.windowWidth, this.windowHeight),
                coroutineContext = glfwDispatcher,
                invalidate = frameDispatcher::scheduleFrame,
                platformContext = PlatformContext.Empty
            )

        NodeLogger.log("Setting callbacks...")
        glfwSetWindowCloseCallback(windowHandle) { window ->
            NodeLogger.group("onWindowCloseCallback")
            NodeLogger.log("onWindowCloseCallback - stopping glfwDispatcher")
            glfwDispatcher.stop()
            NodeLogger.popGroup()
        }

        glfwSetWindowContentScaleCallback(windowHandle) { window, xscale, yscale ->
            NodeLogger.withGroup("onWindowContentScaleCallback(${xscale}x${yscale})") {
                log("setting density")
                setDensity(xscale)
                log("scheduling frame")
                frameDispatcher.scheduleFrame()
            }
        }

        glfwSetFramebufferSizeCallback(windowHandle) { window, width, height ->
            NodeLogger.withGroup("onFramebufferSizeCallback(width=$width, height=$height)") {
                log("New size: (${width}x${height})")
                log("Setting window size")
                setSize(width, height)
                log("Closing and flushing surface...")
                surface.close()
                context.flush()
                log("glFinish()")
                glFinish()
                log("creating new surface...")
                surface = createSurface(windowWidth, windowHeight, context, resize = true)

                glfwSwapInterval(0)
                log("Rendering directly!")
                render(
                    surface = surface,
                    context = context,
                    shaderProgram = shaderManager.programId,
                )
                glfwSwapInterval(1)
            }
        }

        NodeLogger.log("Subscribing composeScene to GLFWEvents.")
        composeScene.subscribeToGLFWEvents(windowHandle) { density.density }
        NodeLogger.log("Setting composeScene content")
        composeScene.setContent {
            App()
        }
        NodeLogger.log("Showing window!")
        glfwShowWindow(windowHandle)

        NodeLogger.log("Starting glfwDispatcher loop")
        glfwDispatcher.runLoop()

        NodeLogger.log("Closing composeScene")
        composeScene.close()
        NodeLogger.log("Deleting composeFbo")
        composeFbo.delete()
        NodeLogger.log("Destroying window")
        glfwDestroyWindow(windowHandle)

        NodeLogger.popGroup()

        exitProcess(0)
    }

    fun setDensity(newDensity: Float, updateComposeScene: Boolean = true) {
        NodeLogger.withGroup("setDensity(density=$newDensity)") {
            density = Density(newDensity)
            if(updateComposeScene) {
                log("Setting composeScene's density")
                composeScene.density = density
            } else {
                log("Won't set composeScene's density")
            }
        }
    }

    fun setSize(width: Int, height: Int, updateComposeScene: Boolean = true) {
        NodeLogger.withGroup("setSize(width=$width, height=$height, updateComposeScene=$updateComposeScene)") {
            windowWidth = width
            windowHeight = height
            if(updateComposeScene) composeScene.size = IntSize(width, height)
        }
    }

    var renderCount = 0
    fun render(
        surface: Surface,
        context: DirectContext,
        shaderProgram: Int,
    ) {
        renderCount++

        // --- 1. Tell Skia to discard ALL cached GL state ---
        // Skia caches which FBO/texture/program/VAO/blend state is bound.
        // Since we modify GL state outside Skia, we must tell it
        // everything is dirty BEFORE we ask it to render.
        context.resetGLAll()

        // --- 2. Render Compose UI via Skia into the FBO ---
        // The Surface is bound to our FBO via makeFromBackendRenderTarget.
        // Skia will bind the correct FBO itself — we must NOT bind/clear
        // it manually, because Skia tracks that state internally.
        surface.canvas.clear(Color.WHITE)
        composeScene.render(surface.canvas.asComposeCanvas(), System.nanoTime())

        // Flush all Skia GPU commands and wait for completion.
        // flushAndSubmit with syncCpu=true ensures all GPU work is done
        // before we read from the FBO texture for blitting.
        context.flushAndSubmit(surface, syncCpu = true)

        // --- 3. Reset GL state after Skia ---
        // Skia leaves GL state dirty (bound programs, VAOs, textures,
        // blend modes, FBOs, etc.). We must restore everything we need.
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, windowWidth, windowHeight)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(0)
        glBindVertexArray(0)

        // --- 4. Clear the default framebuffer ---
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)

        // --- 5. Blit the FBO texture to the screen quad ---
        glUseProgram(shaderProgram)
        glBindVertexArray(composeFbo.quadVaoId)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, composeFbo.colorTextureId)
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)

        // --- 6. Clean up ---
        glBindVertexArray(0)
        glUseProgram(0)

        glfwSwapBuffers(windowHandle)
    }

    private fun createSurface(width: Int, height: Int, context: DirectContext, resize: Boolean = false): Surface {
        NodeLogger.group("createSurface(width=$width, height=$height, context=$context, resize=$resize)")
        when {
            resize -> {
                NodeLogger.log("Resizing composeFbo!")
                composeFbo.resize(width, height)
            }
            else -> {
                NodeLogger.log("Creating composeFbo!")
                composeFbo.create(width, height)
            }
        }
        val fbId = composeFbo.fboId
        NodeLogger.log("Creating BackendRenderTarget and Surface!")
        val renderTarget = BackendRenderTarget.makeGL(width, height, 0, 8, fbId, GR_GL_RGBA8)
        return Surface
            .makeFromBackendRenderTarget(
                context, renderTarget, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
            )
            ?.also { NodeLogger.popGroup() }
            ?: error("Failed to create surface!")
    }
}
