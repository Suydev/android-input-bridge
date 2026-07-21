package com.inputbridge.transport.bt

/**
 * USB HID report descriptor and report ID constants for the InputBridge virtual
 * keyboard + mouse combo device.
 *
 * Report layout (data bytes passed to BluetoothHidDevice.sendReport, without the ID):
 *
 *   Report ID 1 — Keyboard (8 bytes):
 *     byte 0: modifier bitmask
 *               bit 0 = Left Ctrl   bit 4 = Right Ctrl
 *               bit 1 = Left Shift  bit 5 = Right Shift
 *               bit 2 = Left Alt    bit 6 = Right Alt
 *               bit 3 = Left GUI    bit 7 = Right GUI
 *     byte 1: reserved = 0x00
 *     bytes 2–7: up to 6 simultaneous HID usage IDs (0x00 = empty slot)
 *
 *   Report ID 2 — Mouse (4 bytes):
 *     byte 0: button bitmask (bit 0=Left, bit 1=Right, bit 2=Middle)
 *     byte 1: X delta  (signed -127..127, relative)
 *     byte 2: Y delta  (signed -127..127, relative)
 *     byte 3: wheel delta (signed -127..127, positive = scroll down)
 *
 * The descriptor follows USB HID Usage Tables 1.5.
 */
object HidDescriptor {

    const val REPORT_ID_KEYBOARD = 1
    const val REPORT_ID_MOUSE    = 2

    /**
     * Combined HID report descriptor for keyboard (Report ID 1) + mouse (Report ID 2).
     * Passed verbatim to [BluetoothHidDeviceAppSdpSettings] — must be byte-perfect.
     */
    @JvmField
    val DESCRIPTOR: ByteArray = byteArrayOf(
        // ── Keyboard (Report ID 1) ──────────────────────────────────────────────
        0x05.b, 0x01.b,              // Usage Page: Generic Desktop Controls
        0x09.b, 0x06.b,              // Usage: Keyboard
        0xA1.b, 0x01.b,              // Collection: Application
          0x85.b, 0x01.b,            //   Report ID: 1
          // 8 modifier-key bits (Left/Right Ctrl/Shift/Alt/GUI)
          0x05.b, 0x07.b,            //   Usage Page: Keyboard/Keypad
          0x19.b, 0xE0.b,            //   Usage Minimum: Left Control (0xE0)
          0x29.b, 0xE7.b,            //   Usage Maximum: Right GUI    (0xE7)
          0x15.b, 0x00.b,            //   Logical Minimum: 0
          0x25.b, 0x01.b,            //   Logical Maximum: 1
          0x75.b, 0x01.b,            //   Report Size: 1 bit
          0x95.b, 0x08.b,            //   Report Count: 8
          0x81.b, 0x02.b,            //   Input: Data, Variable, Absolute
          // Reserved byte (constant padding)
          0x95.b, 0x01.b,            //   Report Count: 1
          0x75.b, 0x08.b,            //   Report Size: 8 bits
          0x81.b, 0x03.b,            //   Input: Constant
          // Key array: up to 6 simultaneous key usage IDs
          0x05.b, 0x07.b,            //   Usage Page: Keyboard/Keypad
          0x19.b, 0x00.b,            //   Usage Minimum: 0x00
          0x29.b, 0x91.b,            //   Usage Maximum: 0x91 (covers all standard keys)
          0x15.b, 0x00.b,            //   Logical Minimum: 0
          0x26.b, 0x91.b, 0x00.b,   //   Logical Maximum: 0x0091 (145) — two-byte encoding
          0x75.b, 0x08.b,            //   Report Size: 8 bits
          0x95.b, 0x06.b,            //   Report Count: 6
          0x81.b, 0x00.b,            //   Input: Data, Array, Absolute
        0xC0.b,                      // End Collection (Keyboard Application)

        // ── Mouse (Report ID 2) ─────────────────────────────────────────────────
        0x05.b, 0x01.b,              // Usage Page: Generic Desktop Controls
        0x09.b, 0x02.b,              // Usage: Mouse
        0xA1.b, 0x01.b,              // Collection: Application
          0x85.b, 0x02.b,            //   Report ID: 2
          0x09.b, 0x01.b,            //   Usage: Pointer
          0xA1.b, 0x00.b,            //   Collection: Physical
            // 3 button bits (Left, Right, Middle)
            0x05.b, 0x09.b,          //     Usage Page: Buttons
            0x19.b, 0x01.b,          //     Usage Minimum: Button 1 (Left)
            0x29.b, 0x03.b,          //     Usage Maximum: Button 3 (Middle)
            0x15.b, 0x00.b,          //     Logical Minimum: 0
            0x25.b, 0x01.b,          //     Logical Maximum: 1
            0x75.b, 0x01.b,          //     Report Size: 1 bit
            0x95.b, 0x03.b,          //     Report Count: 3
            0x81.b, 0x02.b,          //     Input: Data, Variable, Absolute
            // 5-bit constant padding to align to a full byte
            0x75.b, 0x05.b,          //     Report Size: 5 bits
            0x95.b, 0x01.b,          //     Report Count: 1
            0x81.b, 0x03.b,          //     Input: Constant (padding)
            // X and Y relative movement
            0x05.b, 0x01.b,          //     Usage Page: Generic Desktop
            0x09.b, 0x30.b,          //     Usage: X
            0x09.b, 0x31.b,          //     Usage: Y
            0x15.b, 0x81.b,          //     Logical Minimum: -127
            0x25.b, 0x7F.b,          //     Logical Maximum:  127
            0x75.b, 0x08.b,          //     Report Size: 8 bits
            0x95.b, 0x02.b,          //     Report Count: 2
            0x81.b, 0x06.b,          //     Input: Data, Variable, Relative
            // Scroll wheel
            0x09.b, 0x38.b,          //     Usage: Wheel
            0x15.b, 0x81.b,          //     Logical Minimum: -127
            0x25.b, 0x7F.b,          //     Logical Maximum:  127
            0x75.b, 0x08.b,          //     Report Size: 8 bits
            0x95.b, 0x01.b,          //     Report Count: 1
            0x81.b, 0x06.b,          //     Input: Data, Variable, Relative
          0xC0.b,                    //   End Collection (Physical)
        0xC0.b,                      // End Collection (Mouse Application)
    )

    // Kotlin Byte is signed (-128..127). This extension converts an Int literal to Byte
    // without requiring verbose .toByte() on every line of the descriptor array.
    @Suppress("NOTHING_TO_INLINE")
    private inline val Int.b: Byte get() = toByte()
}
