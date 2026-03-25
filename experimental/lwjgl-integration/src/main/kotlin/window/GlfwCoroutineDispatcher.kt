package window

import kotlinx.coroutines.CoroutineDispatcher
import org.lwjgl.glfw.GLFW
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine dispatcher that integrates with GLFW's event loop.
 *
 * Dispatched tasks are queued and executed on the GLFW thread during [runLoop].
 * When no tasks are pending, the loop blocks on [GLFW.glfwWaitEvents] to avoid
 * busy-waiting. New dispatches call [GLFW.glfwPostEmptyEvent] to wake the loop.
 */
class GlfwCoroutineDispatcher : CoroutineDispatcher() {
    private val tasks = mutableListOf<Runnable>()
    private val tasksCopy = mutableListOf<Runnable>()
    private var isStopped = false

    /**
     * Runs the GLFW event loop on the current thread until [stop] is called.
     * This is the main loop of the application — it processes GLFW events
     * and dispatched coroutine tasks in lockstep.
     */
    fun runLoop() {
        while (!isStopped) {
            synchronized(tasks) {
                tasksCopy.addAll(tasks)
                tasks.clear()
            }
            for (runnable in tasksCopy) {
                if (!isStopped) {
                    runnable.run()
                }
            }
            tasksCopy.clear()
            GLFW.glfwWaitEvents()
        }
    }

    /**
     * Signals the event loop to exit after the current iteration completes.
     */
    fun stop() {
        isStopped = true
        GLFW.glfwPostEmptyEvent()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) {
            tasks.add(block)
        }
        GLFW.glfwPostEmptyEvent()
    }
}
