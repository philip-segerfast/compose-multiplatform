package window

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import org.lwjgl.glfw.GLFW.*

/**
 * A [PlatformContext] backed by GLFW, providing real cursor icon support.
 *
 * This extends the behavior of [PlatformContext.Empty] by implementing
 * [setPointerIcon] to change the GLFW cursor shape. Without this, Compose
 * text fields won't show the I-beam cursor on hover, and drag handles won't
 * show resize cursors.
 *
 * ## Cursor lifecycle
 *
 * Standard cursors created via [glfwCreateStandardCursor] are owned by GLFW
 * and don't need manual cleanup — they're destroyed when [glfwTerminate] is
 * called. However, we cache them to avoid creating new cursor objects every
 * time Compose requests a pointer icon change (which can happen on every
 * mouse move event during hover).
 *
 * @param windowHandle the GLFW window handle to set cursors on
 */
@OptIn(InternalComposeUiApi::class)
class GlfwPlatformContext(
    private val windowHandle: Long,
) : PlatformContext by PlatformContext.Empty {

    // Cache of standard cursors to avoid repeated allocation.
    // GLFW standard cursors are lightweight, but creating them on every
    // mouse move would be wasteful.
    private val cursorCache = mutableMapOf<Int, Long>()

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        // Compose's PointerIcon on desktop wraps an AWT Cursor type.
        // We map the AWT cursor type constants to GLFW standard cursor shapes.
        val glfwCursorShape = mapPointerIconToGlfwCursor(pointerIcon)

        if (glfwCursorShape == null) {
            // Default arrow cursor — pass null to reset to the window's default
            glfwSetCursor(windowHandle, 0)
        } else {
            val cursor = cursorCache.getOrPut(glfwCursorShape) {
                glfwCreateStandardCursor(glfwCursorShape)
            }
            glfwSetCursor(windowHandle, cursor)
        }
    }

    /**
     * Maps a Compose [PointerIcon] to a GLFW standard cursor shape constant.
     *
     * Compose Desktop's [PointerIcon] wraps a [java.awt.Cursor] internally.
     * We extract the AWT cursor type and map it to the nearest GLFW equivalent.
     *
     * Returns `null` for the default arrow cursor (GLFW uses `null` to reset).
     */
    private fun mapPointerIconToGlfwCursor(pointerIcon: PointerIcon): Int? {
        // PointerIcon on desktop is backed by java.awt.Cursor.
        // The predefined icons (PointerIcon.Text, PointerIcon.Hand, etc.)
        // use standard AWT cursor types.
        val awtCursor = try {
            // PointerIcon wraps an AWT Cursor — extract its type
            val cursor = pointerIcon.javaClass.getDeclaredField("cursor").apply {
                isAccessible = true
            }.get(pointerIcon) as? java.awt.Cursor
            cursor?.type
        } catch (_: Exception) {
            null
        }

        return when (awtCursor) {
            java.awt.Cursor.TEXT_CURSOR -> GLFW_IBEAM_CURSOR
            java.awt.Cursor.HAND_CURSOR -> GLFW_HAND_CURSOR
            java.awt.Cursor.CROSSHAIR_CURSOR -> GLFW_CROSSHAIR_CURSOR
            java.awt.Cursor.N_RESIZE_CURSOR,
            java.awt.Cursor.S_RESIZE_CURSOR -> GLFW_VRESIZE_CURSOR
            java.awt.Cursor.E_RESIZE_CURSOR,
            java.awt.Cursor.W_RESIZE_CURSOR -> GLFW_HRESIZE_CURSOR
            java.awt.Cursor.NW_RESIZE_CURSOR,
            java.awt.Cursor.SE_RESIZE_CURSOR -> GLFW_RESIZE_NWSE_CURSOR
            java.awt.Cursor.NE_RESIZE_CURSOR,
            java.awt.Cursor.SW_RESIZE_CURSOR -> GLFW_RESIZE_NESW_CURSOR
            java.awt.Cursor.MOVE_CURSOR -> GLFW_RESIZE_ALL_CURSOR
            else -> null // Default arrow
        }
    }
}
