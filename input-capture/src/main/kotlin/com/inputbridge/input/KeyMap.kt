package com.inputbridge.input

import android.view.KeyEvent

/**
 * Maps USB HID scan codes and Android key codes to InputBridge key codes.
 *
 * Key code strategy:
 * - InputBridge uses Android's KeyEvent key codes as the canonical representation.
 * - USB HID scan codes are translated to Android key codes during capture.
 * - This avoids needing a second mapping on the receiver side.
 *
 * References:
 * - HID Usage Tables: https://usb.org/document-library/hid-usage-tables-15
 * - Android KeyEvent: https://developer.android.com/reference/android/view/KeyEvent
 */
object KeyMap {

    /**
     * Map a USB HID keyboard scan code (Usage ID from page 0x07) to
     * an Android KeyEvent key code.
     *
     * Returns [KeyEvent.KEYCODE_UNKNOWN] for unmapped codes.
     */
    fun hidToAndroid(hidUsageId: Int): Int = HID_TO_ANDROID.getOrDefault(
        hidUsageId, KeyEvent.KEYCODE_UNKNOWN
    )

    /**
     * Determine if a key code represents a modifier key.
     */
    fun isModifier(keyCode: Int): Boolean = keyCode in MODIFIER_KEY_CODES

    // ── HID Usage ID → Android KeyCode map ───────────────────────────────────
    // Keyboard/Keypad page (Usage Page 0x07)
    private val HID_TO_ANDROID: Map<Int, Int> = mapOf(
        0x04 to KeyEvent.KEYCODE_A,
        0x05 to KeyEvent.KEYCODE_B,
        0x06 to KeyEvent.KEYCODE_C,
        0x07 to KeyEvent.KEYCODE_D,
        0x08 to KeyEvent.KEYCODE_E,
        0x09 to KeyEvent.KEYCODE_F,
        0x0A to KeyEvent.KEYCODE_G,
        0x0B to KeyEvent.KEYCODE_H,
        0x0C to KeyEvent.KEYCODE_I,
        0x0D to KeyEvent.KEYCODE_J,
        0x0E to KeyEvent.KEYCODE_K,
        0x0F to KeyEvent.KEYCODE_L,
        0x10 to KeyEvent.KEYCODE_M,
        0x11 to KeyEvent.KEYCODE_N,
        0x12 to KeyEvent.KEYCODE_O,
        0x13 to KeyEvent.KEYCODE_P,
        0x14 to KeyEvent.KEYCODE_Q,
        0x15 to KeyEvent.KEYCODE_R,
        0x16 to KeyEvent.KEYCODE_S,
        0x17 to KeyEvent.KEYCODE_T,
        0x18 to KeyEvent.KEYCODE_U,
        0x19 to KeyEvent.KEYCODE_V,
        0x1A to KeyEvent.KEYCODE_W,
        0x1B to KeyEvent.KEYCODE_X,
        0x1C to KeyEvent.KEYCODE_Y,
        0x1D to KeyEvent.KEYCODE_Z,
        // Numbers
        0x1E to KeyEvent.KEYCODE_1,
        0x1F to KeyEvent.KEYCODE_2,
        0x20 to KeyEvent.KEYCODE_3,
        0x21 to KeyEvent.KEYCODE_4,
        0x22 to KeyEvent.KEYCODE_5,
        0x23 to KeyEvent.KEYCODE_6,
        0x24 to KeyEvent.KEYCODE_7,
        0x25 to KeyEvent.KEYCODE_8,
        0x26 to KeyEvent.KEYCODE_9,
        0x27 to KeyEvent.KEYCODE_0,
        // Control
        0x28 to KeyEvent.KEYCODE_ENTER,
        0x29 to KeyEvent.KEYCODE_ESCAPE,
        0x2A to KeyEvent.KEYCODE_DEL, // Backspace
        0x2B to KeyEvent.KEYCODE_TAB,
        0x2C to KeyEvent.KEYCODE_SPACE,
        0x2D to KeyEvent.KEYCODE_MINUS,
        0x2E to KeyEvent.KEYCODE_EQUALS,
        0x2F to KeyEvent.KEYCODE_LEFT_BRACKET,
        0x30 to KeyEvent.KEYCODE_RIGHT_BRACKET,
        0x31 to KeyEvent.KEYCODE_BACKSLASH,
        0x33 to KeyEvent.KEYCODE_SEMICOLON,
        0x34 to KeyEvent.KEYCODE_APOSTROPHE,
        0x35 to KeyEvent.KEYCODE_GRAVE,
        0x36 to KeyEvent.KEYCODE_COMMA,
        0x37 to KeyEvent.KEYCODE_PERIOD,
        0x38 to KeyEvent.KEYCODE_SLASH,
        0x39 to KeyEvent.KEYCODE_CAPS_LOCK,
        // Function keys
        0x3A to KeyEvent.KEYCODE_F1,
        0x3B to KeyEvent.KEYCODE_F2,
        0x3C to KeyEvent.KEYCODE_F3,
        0x3D to KeyEvent.KEYCODE_F4,
        0x3E to KeyEvent.KEYCODE_F5,
        0x3F to KeyEvent.KEYCODE_F6,
        0x40 to KeyEvent.KEYCODE_F7,
        0x41 to KeyEvent.KEYCODE_F8,
        0x42 to KeyEvent.KEYCODE_F9,
        0x43 to KeyEvent.KEYCODE_F10,
        0x44 to KeyEvent.KEYCODE_F11,
        0x45 to KeyEvent.KEYCODE_F12,
        // Navigation
        0x4A to KeyEvent.KEYCODE_MOVE_HOME,
        0x4B to KeyEvent.KEYCODE_PAGE_UP,
        0x4C to KeyEvent.KEYCODE_FORWARD_DEL, // Delete
        0x4D to KeyEvent.KEYCODE_MOVE_END,
        0x4E to KeyEvent.KEYCODE_PAGE_DOWN,
        0x4F to KeyEvent.KEYCODE_DPAD_RIGHT,
        0x50 to KeyEvent.KEYCODE_DPAD_LEFT,
        0x51 to KeyEvent.KEYCODE_DPAD_DOWN,
        0x52 to KeyEvent.KEYCODE_DPAD_UP,
        // Modifiers
        0xE0 to KeyEvent.KEYCODE_CTRL_LEFT,
        0xE1 to KeyEvent.KEYCODE_SHIFT_LEFT,
        0xE2 to KeyEvent.KEYCODE_ALT_LEFT,
        0xE3 to KeyEvent.KEYCODE_META_LEFT,
        0xE4 to KeyEvent.KEYCODE_CTRL_RIGHT,
        0xE5 to KeyEvent.KEYCODE_SHIFT_RIGHT,
        0xE6 to KeyEvent.KEYCODE_ALT_RIGHT,
        0xE7 to KeyEvent.KEYCODE_META_RIGHT,
    )

    private val MODIFIER_KEY_CODES = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
    )
}
