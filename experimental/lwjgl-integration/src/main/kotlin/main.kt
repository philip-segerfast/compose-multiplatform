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
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.system.exitProcess
import kotlin.use

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
//    val windowInfo: WindowInfo = GlfwWindowInfoImpl()

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

        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)

        windowHandle = glfwCreateWindow(windowWidth, windowHeight, "Compose LWJGL Demo", NULL, NULL)
        glfwMakeContextCurrent(windowHandle)
        glfwSwapInterval(1)

        GL.createCapabilities()

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
            RootContainer(
//                windowInfo = windowInfo
            ) {
                App()
            }
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
    ): Unit = NodeLogger.withGroup("render") {
        log("render(surface=$surface, context=$context, shaderProgram=$shaderProgram)")
        renderCount++
        if(renderCount % 100 == 0) {
            log("[${renderCount}] Rendering x100")
        }

        bindFramebuffer(GL_FRAMEBUFFER, composeFbo.fboId)

        // Enable blending for proper text and transparency rendering
        enable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        log("Clearing surface canvas")
        surface.canvas.clear(Color.WHITE)
        log("Rendering composeScene to surface canvas (to fbo)")
        composeScene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
        log("Flushing context")
        context.flush()

        disable(GL_BLEND)

        // Now, render that FBO's texture to the screen
        // Bind to default frambuffer
        bindFramebuffer(GL_FRAMEBUFFER, 0)

        useProgram(shaderProgram)
        bindVertexArray(composeFbo.quadVaoId)
        bindTexture(GL_TEXTURE_2D, composeFbo.colorTextureId)
        log("Drawing texture")
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)
        bindVertexArray(0)
        useProgram(0)

        // Swap buffers to display the rendered frame
        log("Swapping buffers")
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

//internal class GlfwWindowInfoImpl : WindowInfo {
//    private val _containerSize = mutableStateOf(IntSize.Zero)
//
//    override var isWindowFocused: Boolean by mutableStateOf(false)
//
//    override var keyboardModifiers: PointerKeyboardModifiers
//        get() = GlobalKeyboardModifiers.value
//        set(value) {
//            GlobalKeyboardModifiers.value = value
//        }
//
//    override var containerSize: IntSize
//        get() = _containerSize.value
//        set(value) {
//            _containerSize.value = value
//        }
//
//    companion object {
//        // One instance across all windows makes sense, since the state of KeyboardModifiers is
//        // common for all windows.
//        internal val GlobalKeyboardModifiers = mutableStateOf(EmptyPointerKeyboardModifiers())
//    }
//}

//@OptIn(InternalComposeUiApi::class)
//class GlfwPlatformContext : PlatformContext {
//    override val windowInfo: WindowInfo = GlfwWindowInfoImpl()
//
//    // Todo - override in Minecraft
//    override val screenReader: PlatformScreenReader = object : PlatformScreenReader {
//        override val isActive: Boolean = false
//    }
//
//    override val inputModeManager: InputModeManager = object : InputModeManager {
//        // Only keyboard is supported
//        override val inputMode: InputMode = InputMode.Keyboard
//
//        override fun requestInputMode(inputMode: InputMode): Boolean {
//            return inputMode == InputMode.Keyboard
//        }
//    }
//
//
//
//}







