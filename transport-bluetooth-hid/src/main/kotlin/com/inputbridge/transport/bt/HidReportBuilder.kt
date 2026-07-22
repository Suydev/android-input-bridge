package com.inputbridge.transport.bt

import android.view.KeyEvent
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.ModifierState
import com.inputbridge.core.model.MouseButton

/**
 * Converts [InputEvent] objects into USB HID report byte arrays.
 *
 * Maintains stateful key and button tracking so each report reflects the
 * current device state correctly:
 *   - Keyboard: up to 6 simultaneously pressed non-modifier keys
 *   - Mouse: current button bitmask (buttons latch until released)
 *
 * HID report format (data bytes only — Report ID is passed separately):
 *
 *   Keyboard (Report ID [HidDescriptor.REPORT_ID_KEYBOARD]), 8 bytes:
 *     [0] modifier bitmask (Left Ctrl=0x01, Left Shift=0x02, Left Alt=0x04, Left GUI=0x08)
 *     [1] reserved = 0x00
 *     [2..7] HID usage IDs for pressed keys (0x00 = empty slot)
 *
 *   Mouse (Report ID [HidDescriptor.REPORT_ID_MOUSE]), 4 bytes:
 *     [0] button bitmask (bit 0=Left, bit 1=Right, bit 2=Middle)
 *     [1] X delta — signed, -127..127 (right = positive)
 *     [2] Y delta — signed, -127..127 (down  = positive)
 *     [3] wheel  — signed, -127..127 (scroll down = positive per HID convention)
 *
 * Thread safety: single-threaded (called from the USB capture coroutine only).
 */
class HidReportBuilder {

    // ── State ──────────────────────────────────────────────────────────────────

    /** HID usage IDs of currently held non-modifier keys (max 6 per HID spec). */
    private val pressedKeys = ArrayDeque<Int>(6)

    /** Current mouse button state bitmask. */
    private var mouseButtonMask: Int = 0

    // ── Keyboard reports ───────────────────────────────────────────────────────

    /** Key pressed. Returns a full 8-byte keyboard report. */
    fun onKeyDown(event: InputEvent.KeyDown): ByteArray {
        ANDROID_TO_HID[event.keyCode]?.let { hid ->
            if (!pressedKeys.contains(hid) && pressedKeys.size < 6) {
                pressedKeys.addLast(hid)
            }
        }
        return buildKeyboardReport(event.modifiers)
    }

    /** Key released. Returns a full 8-byte keyboard report. */
    fun onKeyUp(event: InputEvent.KeyUp): ByteArray {
        ANDROID_TO_HID[event.keyCode]?.let { pressedKeys.remove(it) }
        return buildKeyboardReport(event.modifiers)
    }

    /**
     * Returns an all-keys-released keyboard report and resets all state.
     * Send this before disconnecting to avoid stuck keys on the host.
     */
    fun buildAllRelease(): ByteArray {
        pressedKeys.clear()
        mouseButtonMask = 0
        return buildKeyboardReport(ModifierState.NONE)
    }

    // ── Mouse reports ──────────────────────────────────────────────────────────

    /** Relative pointer movement. Returns a 4-byte mouse report. */
    fun onMouseMove(dx: Float, dy: Float): ByteArray =
        buildMouseReport(dx = dx.clampToByte(), dy = dy.clampToByte(), wheel = 0)

    /** Mouse button pressed. Returns a 4-byte mouse report. */
    fun onMouseButtonDown(button: MouseButton): ByteArray {
        mouseButtonMask = mouseButtonMask or buttonBit(button)
        return buildMouseReport(dx = 0, dy = 0, wheel = 0)
    }

    /** Mouse button released. Returns a 4-byte mouse report. */
    fun onMouseButtonUp(button: MouseButton): ByteArray {
        mouseButtonMask = mouseButtonMask and buttonBit(button).inv()
        return buildMouseReport(dx = 0, dy = 0, wheel = 0)
    }

    /**
     * Scroll wheel. dy > 0 = scroll down (content moves up), dy < 0 = scroll up.
     * Per HID convention, positive wheel value = scroll toward user (down).
     */
    fun onScroll(dy: Float): ByteArray =
        buildMouseReport(dx = 0, dy = 0, wheel = (-dy).clampToByte())

    // ── Private builders ───────────────────────────────────────────────────────

    private fun buildKeyboardReport(mods: ModifierState): ByteArray {
        val report = ByteArray(8)
        report[0] = hidModifierByte(mods)
        report[1] = 0x00
        pressedKeys.forEachIndexed { i, usage -> report[2 + i] = usage.toByte() }
        return report
    }

    private fun buildMouseReport(dx: Int, dy: Int, wheel: Int): ByteArray =
        byteArrayOf(mouseButtonMask.toByte(), dx.toByte(), dy.toByte(), wheel.toByte())

    /**
     * Map [ModifierState] to the HID keyboard modifier byte.
     *
     * InputBridge does not distinguish left/right variants (the USB keyboard
     * already resolved this in the capture layer), so we always set the left
     * modifier bit for each active modifier.
     *
     *   Bit layout: LeftCtrl=0x01, LeftShift=0x02, LeftAlt=0x04, LeftGUI=0x08,
     *               RightCtrl=0x10, RightShift=0x20, RightAlt=0x40, RightGUI=0x80
     */
    private fun hidModifierByte(mods: ModifierState): Byte {
        var b = 0
        if (mods.ctrl)               b = b or 0x01  // Left Ctrl
        if (mods.shift || mods.capsLock) b = b or 0x02  // Left Shift
        if (mods.alt)                b = b or 0x04  // Left Alt
        if (mods.meta)               b = b or 0x08  // Left GUI / Super
        return b.toByte()
    }

    private fun buttonBit(button: MouseButton): Int = when (button) {
        MouseButton.LEFT   -> 0x01
        MouseButton.RIGHT  -> 0x02
        MouseButton.MIDDLE -> 0x04
        else               -> 0x00
    }

    private fun Float.clampToByte(): Int = coerceIn(-127f, 127f).toInt()

    // ── Android KEYCODE → HID usage code table ────────────────────────────────
    //
    // This is the exact inverse of KeyMap.HID_TO_ANDROID (input-capture module).
    // Modifier keycodes (0xE0–0xE7) are intentionally excluded here — they are
    // encoded via hidModifierByte(), not placed in the key-array slots.

    companion object {
        @JvmStatic
        val ANDROID_TO_HID: Map<Int, Int> = mapOf(
            // Letters A–Z
            KeyEvent.KEYCODE_A to 0x04,
            KeyEvent.KEYCODE_B to 0x05,
            KeyEvent.KEYCODE_C to 0x06,
            KeyEvent.KEYCODE_D to 0x07,
            KeyEvent.KEYCODE_E to 0x08,
            KeyEvent.KEYCODE_F to 0x09,
            KeyEvent.KEYCODE_G to 0x0A,
            KeyEvent.KEYCODE_H to 0x0B,
            KeyEvent.KEYCODE_I to 0x0C,
            KeyEvent.KEYCODE_J to 0x0D,
            KeyEvent.KEYCODE_K to 0x0E,
            KeyEvent.KEYCODE_L to 0x0F,
            KeyEvent.KEYCODE_M to 0x10,
            KeyEvent.KEYCODE_N to 0x11,
            KeyEvent.KEYCODE_O to 0x12,
            KeyEvent.KEYCODE_P to 0x13,
            KeyEvent.KEYCODE_Q to 0x14,
            KeyEvent.KEYCODE_R to 0x15,
            KeyEvent.KEYCODE_S to 0x16,
            KeyEvent.KEYCODE_T to 0x17,
            KeyEvent.KEYCODE_U to 0x18,
            KeyEvent.KEYCODE_V to 0x19,
            KeyEvent.KEYCODE_W to 0x1A,
            KeyEvent.KEYCODE_X to 0x1B,
            KeyEvent.KEYCODE_Y to 0x1C,
            KeyEvent.KEYCODE_Z to 0x1D,
            // Number row 1–0
            KeyEvent.KEYCODE_1 to 0x1E,
            KeyEvent.KEYCODE_2 to 0x1F,
            KeyEvent.KEYCODE_3 to 0x20,
            KeyEvent.KEYCODE_4 to 0x21,
            KeyEvent.KEYCODE_5 to 0x22,
            KeyEvent.KEYCODE_6 to 0x23,
            KeyEvent.KEYCODE_7 to 0x24,
            KeyEvent.KEYCODE_8 to 0x25,
            KeyEvent.KEYCODE_9 to 0x26,
            KeyEvent.KEYCODE_0 to 0x27,
            // Control / whitespace
            KeyEvent.KEYCODE_ENTER         to 0x28,
            KeyEvent.KEYCODE_ESCAPE        to 0x29,
            KeyEvent.KEYCODE_DEL           to 0x2A,  // Backspace (Android DEL = USB Backspace)
            KeyEvent.KEYCODE_TAB           to 0x2B,
            KeyEvent.KEYCODE_SPACE         to 0x2C,
            // Punctuation / symbols
            KeyEvent.KEYCODE_MINUS         to 0x2D,
            KeyEvent.KEYCODE_EQUALS        to 0x2E,
            KeyEvent.KEYCODE_LEFT_BRACKET  to 0x2F,
            KeyEvent.KEYCODE_RIGHT_BRACKET to 0x30,
            KeyEvent.KEYCODE_BACKSLASH     to 0x31,
            // 0x32 = Non-US # / ~ (omitted — rare)
            KeyEvent.KEYCODE_SEMICOLON     to 0x33,
            KeyEvent.KEYCODE_APOSTROPHE    to 0x34,
            KeyEvent.KEYCODE_GRAVE         to 0x35,
            KeyEvent.KEYCODE_COMMA         to 0x36,
            KeyEvent.KEYCODE_PERIOD        to 0x37,
            KeyEvent.KEYCODE_SLASH         to 0x38,
            KeyEvent.KEYCODE_CAPS_LOCK     to 0x39,
            // Function keys F1–F12
            KeyEvent.KEYCODE_F1            to 0x3A,
            KeyEvent.KEYCODE_F2            to 0x3B,
            KeyEvent.KEYCODE_F3            to 0x3C,
            KeyEvent.KEYCODE_F4            to 0x3D,
            KeyEvent.KEYCODE_F5            to 0x3E,
            KeyEvent.KEYCODE_F6            to 0x3F,
            KeyEvent.KEYCODE_F7            to 0x40,
            KeyEvent.KEYCODE_F8            to 0x41,
            KeyEvent.KEYCODE_F9            to 0x42,
            KeyEvent.KEYCODE_F10           to 0x43,
            KeyEvent.KEYCODE_F11           to 0x44,
            KeyEvent.KEYCODE_F12           to 0x45,
            // PrintScreen, ScrollLock, Pause — Android seldom produces these but map them
            KeyEvent.KEYCODE_SYSRQ         to 0x46,  // Print Screen
            KeyEvent.KEYCODE_SCROLL_LOCK   to 0x47,
            KeyEvent.KEYCODE_BREAK         to 0x48,  // Pause/Break
            // Navigation cluster
            KeyEvent.KEYCODE_INSERT        to 0x49,
            KeyEvent.KEYCODE_MOVE_HOME     to 0x4A,
            KeyEvent.KEYCODE_PAGE_UP       to 0x4B,
            KeyEvent.KEYCODE_FORWARD_DEL   to 0x4C,  // Delete (forward delete)
            KeyEvent.KEYCODE_MOVE_END      to 0x4D,
            KeyEvent.KEYCODE_PAGE_DOWN     to 0x4E,
            // Arrow keys
            KeyEvent.KEYCODE_DPAD_RIGHT    to 0x4F,
            KeyEvent.KEYCODE_DPAD_LEFT     to 0x50,
            KeyEvent.KEYCODE_DPAD_DOWN     to 0x51,
            KeyEvent.KEYCODE_DPAD_UP       to 0x52,
            // Numpad
            KeyEvent.KEYCODE_NUM_LOCK      to 0x53,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE to 0x54,
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 0x55,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 0x56,
            KeyEvent.KEYCODE_NUMPAD_ADD    to 0x57,
            KeyEvent.KEYCODE_NUMPAD_ENTER  to 0x58,
            KeyEvent.KEYCODE_NUMPAD_1      to 0x59,
            KeyEvent.KEYCODE_NUMPAD_2      to 0x5A,
            KeyEvent.KEYCODE_NUMPAD_3      to 0x5B,
            KeyEvent.KEYCODE_NUMPAD_4      to 0x5C,
            KeyEvent.KEYCODE_NUMPAD_5      to 0x5D,
            KeyEvent.KEYCODE_NUMPAD_6      to 0x5E,
            KeyEvent.KEYCODE_NUMPAD_7      to 0x5F,
            KeyEvent.KEYCODE_NUMPAD_8      to 0x60,
            KeyEvent.KEYCODE_NUMPAD_9      to 0x61,
            KeyEvent.KEYCODE_NUMPAD_0      to 0x62,
            KeyEvent.KEYCODE_NUMPAD_DOT    to 0x63,
            // BUG-050 fix: KEYCODE_MENU (HID Application / Menu key, usage 0x65) was missing.
            // NOTE: KEYCODE_F13–F24 (HID 0x68–0x73) are NOT added here — those constants do
            // not exist in android.view.KeyEvent (Android only defines F1–F12). See BUG-054.
            // If a device sends F13–F24 it goes via KeyMap → KEYCODE_UNKNOWN → dropped, which
            // is the correct and safe behaviour on both capture and BT HID paths.
            KeyEvent.KEYCODE_MENU          to 0x65,  // HID Application / Menu key
        )
    }
}
