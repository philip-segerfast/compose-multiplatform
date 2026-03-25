package window

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import org.lwjgl.glfw.GLFW.*
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Subscribes this [ComposeScene] to GLFW input events (mouse, keyboard, scroll).
 *
 * Bridges GLFW callbacks into Compose's pointer/key event system. Compose
 * currently requires AWT event wrappers internally — this will be removed
 * in future Compose versions.
 *
 * @param windowHandle   the GLFW window to listen on
 * @param densityProvider returns the current display density scale factor
 */
@OptIn(InternalComposeUiApi::class)
fun ComposeScene.subscribeToGLFWEvents(windowHandle: Long, densityProvider: () -> Float) {
    glfwSetMouseButtonCallback(windowHandle) { _, button, action, mods ->
        sendPointerEvent(
            position = glfwGetCursorPos(windowHandle, densityProvider()),
            eventType = when (action) {
                GLFW_PRESS -> PointerEventType.Press
                GLFW_RELEASE -> PointerEventType.Release
                else -> PointerEventType.Unknown
            },
            nativeEvent = MouseEvent(getAwtMods(windowHandle)),
        )
    }

    glfwSetCursorPosCallback(windowHandle) { _, xpos, ypos ->
        val density = densityProvider()
        sendPointerEvent(
            position = Offset(xpos.toFloat() * density, ypos.toFloat() * density),
            eventType = PointerEventType.Move,
            nativeEvent = MouseEvent(getAwtMods(windowHandle)),
        )
    }

    glfwSetCursorEnterCallback(windowHandle) { _, entered ->
        sendPointerEvent(
            position = glfwGetCursorPos(windowHandle, densityProvider()),
            eventType = if (entered) PointerEventType.Enter else PointerEventType.Exit,
            nativeEvent = MouseEvent(getAwtMods(windowHandle)),
        )
    }

    glfwSetScrollCallback(windowHandle) { _, xoffset, yoffset ->
        sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = glfwGetCursorPos(windowHandle, densityProvider()),
            scrollDelta = Offset(xoffset.toFloat(), -yoffset.toFloat()),
            nativeEvent = MouseWheelEvent(getAwtMods(windowHandle)),
        )
    }

    glfwSetKeyCallback(windowHandle) { _, key, _, action, _ ->
        val awtId = when (action) {
            GLFW_PRESS -> AwtKeyEvent.KEY_PRESSED
            GLFW_REPEAT -> AwtKeyEvent.KEY_PRESSED
            GLFW_RELEASE -> AwtKeyEvent.KEY_RELEASED
            else -> error("Unknown GLFW key action: $action")
        }
        val awtKey = glfwToAwtKeyCode(key)
        val time = System.nanoTime() / 1_000_000

        sendKeyEvent(
            KeyEvent(
                awtId, time, getAwtMods(windowHandle),
                awtKey, 0.toChar(), AwtKeyEvent.KEY_LOCATION_STANDARD,
            )
        )
    }

    glfwSetCharCallback(windowHandle) { _, codepoint ->
        for (char in Character.toChars(codepoint)) {
            val time = System.nanoTime() / 1_000_000
            sendKeyEvent(
                KeyEvent(
                    AwtKeyEvent.KEY_TYPED, time, getAwtMods(windowHandle),
                    0, char, AwtKeyEvent.KEY_LOCATION_UNKNOWN,
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private fun glfwGetCursorPos(window: Long, density: Float): Offset {
    val x = DoubleArray(1)
    val y = DoubleArray(1)
    glfwGetCursorPos(window, x, y)
    return Offset(x[0].toFloat() * density, y[0].toFloat() * density)
}

/**
 * Dummy AWT component required by Compose's event system.
 * Will be unnecessary in future Compose versions.
 */
private val awtComponent = object : Component() {}

@OptIn(InternalComposeUiApi::class)
private fun KeyEvent(
    awtId: Int, time: Long, awtMods: Int, key: Int, char: Char, location: Int,
) = KeyEvent(
    key = Key(key),
    codePoint = char.code,
    type = when (awtId) {
        AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
        AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
        else -> KeyEventType.Unknown
    },
    nativeEvent = AwtKeyEvent(awtComponent, awtId, time, awtMods, key, char, location),
)

private fun MouseEvent(awtMods: Int) = MouseEvent(
    awtComponent, 0, 0, awtMods, 0, 0, 1, false,
)

private fun MouseWheelEvent(awtMods: Int) = MouseWheelEvent(
    awtComponent, 0, 0, awtMods, 0, 0, 1, false,
    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1,
)

private fun getAwtMods(windowHandle: Long): Int {
    var mods = 0
    if (glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_1) == GLFW_PRESS)
        mods = mods or InputEvent.BUTTON1_DOWN_MASK
    if (glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_2) == GLFW_PRESS)
        mods = mods or InputEvent.BUTTON2_DOWN_MASK
    if (glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_3) == GLFW_PRESS)
        mods = mods or InputEvent.BUTTON3_DOWN_MASK
    if (glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_4) == GLFW_PRESS)
        mods = mods or (1 shl 14)
    if (glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_5) == GLFW_PRESS)
        mods = mods or (1 shl 15)
    if (glfwGetKey(windowHandle, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS ||
        glfwGetKey(windowHandle, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS)
        mods = mods or InputEvent.CTRL_DOWN_MASK
    if (glfwGetKey(windowHandle, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS ||
        glfwGetKey(windowHandle, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS)
        mods = mods or InputEvent.SHIFT_DOWN_MASK
    if (glfwGetKey(windowHandle, GLFW_KEY_LEFT_ALT) == GLFW_PRESS ||
        glfwGetKey(windowHandle, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS)
        mods = mods or InputEvent.ALT_DOWN_MASK
    return mods
}
