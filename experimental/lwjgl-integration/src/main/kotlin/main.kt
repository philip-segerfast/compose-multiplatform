import window.GlfwWindow

fun main() {
    // Required for Skiko to use OpenGL on macOS instead of Metal
    System.setProperty("skiko.macos.opengl.enabled", "true")
    // Prevent AWT from trying to open a display connection (we use GLFW instead)
    System.setProperty("java.awt.headless", "true")

    GlfwWindow(width = 640, height = 480, title = "Compose LWJGL Demo").run {
        App()
    }
}
