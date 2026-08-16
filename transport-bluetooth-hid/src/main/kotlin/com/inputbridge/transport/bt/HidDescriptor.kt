package com.inputbridge.transport.bt

/**
 * USB HID report descriptor and report ID constants for the InputBridge virtual
 * keyboard + mouse combo device.
 *
 * Report layout (data bytes passed to BluetoothHidDevice.sendReport, without the ID):
 *
 *   Report ID 1 — Keyboard (8 bytes data, no Report ID byte on the wire):
 *     byte 0: modifier bitmask
 *               bit 0 = Left Ctrl   bit 4 = Right Ctrl
 *               bit 1 = Left Shift  bit 5 = Right Shift
 *               bit 2 = Left Alt    bit 6 = Right Alt
 *               bit 3 = Left GUI    bit 7 = Right GUI
 *     byte 1: reserved = 0x00
 *     bytes 2–7: up to 6 simultaneous HID usage IDs (0x00 = empty slot)
 *     (host→device LED Output report: 1 byte for Num/Caps/Scroll/Kana/Compose)
 *
 *   Report ID 2 — Mouse (5 bytes data):
 *     byte 0: button bitmask (bit 0=Left, 1=Right, 2=Middle, 3=Back, 4=Forward)
 *     byte 1: X delta  (signed -127..127, relative)
 *     byte 2: Y delta  (signed -127..127, relative)
 *     byte 3: wheel delta (signed -127..127, positive = scroll down)
 *     byte 4: AC Pan delta (signed -127..127, positive = scroll right) — Consumer page
 *
 * The descriptor follows USB HID Usage Tables 1.5 and matches the boot-protocol
 * layouts used by the reference app "Bluetooth Keyboard Mouse" (decompiled) which
 * is known to work as a HID keyboard+mouse on any Bluetooth host. BUG-138: keyboard
 * usage maximum is the boot-standard 0x65 (101 keys), the mouse exposes 5 buttons
 * plus AC Pan horizontal scroll, and the keyboard declares an LED Output collection
 * so hosts can send Caps/Num Lock state.
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
           // Key array: up to 6 simultaneous key usage IDs (boot-standard 0x65 = 101 keys)
           0x05.b, 0x07.b,            //   Usage Page: Keyboard/Keypad
           0x19.b, 0x00.b,            //   Usage Minimum: 0x00
           0x29.b, 0x65.b,            //   Usage Maximum: 0x65 (101) — boot keyboard maximum
           0x15.b, 0x00.b,            //   Logical Minimum: 0
           0x25.b, 0x65.b,            //   Logical Maximum: 0x65 (101)
           0x75.b, 0x08.b,            //   Report Size: 8 bits
           0x95.b, 0x06.b,            //   Report Count: 6
           0x81.b, 0x00.b,            //   Input: Data, Array, Absolute
           // LED Output collection (host → device): Num/Caps/Scroll/Kana/Compose
           0x05.b, 0x08.b,            //   Usage Page: LEDs
           0x19.b, 0x01.b,            //   Usage Minimum: 0x01 (Num Lock)
           0x29.b, 0x05.b,            //   Usage Maximum: 0x05 (Kana)
           0x15.b, 0x00.b,            //   Logical Minimum: 0
           0x25.b, 0x01.b,            //   Logical Maximum: 1
           0x75.b, 0x01.b,            //   Report Size: 1 bit
           0x95.b, 0x05.b,            //   Report Count: 5
           0x91.b, 0x02.b,            //   Output: Data, Variable, Absolute
           0x75.b, 0x03.b,            //   Report Size: 3 bits (padding)
           0x95.b, 0x01.b,            //   Report Count: 1
           0x91.b, 0x03.b,            //   Output: Constant (padding) → 1-byte LED report
         0xC0.b,                      // End Collection (Keyboard Application)

         // ── Mouse (Report ID 2) ─────────────────────────────────────────────────
         0x05.b, 0x01.b,              // Usage Page: Generic Desktop Controls
         0x09.b, 0x02.b,              // Usage: Mouse
         0xA1.b, 0x01.b,              // Collection: Application
           0x85.b, 0x02.b,            //   Report ID: 2
           0x09.b, 0x01.b,            //   Usage: Pointer
           0xA1.b, 0x00.b,            //   Collection: Physical
             // 5 button bits (Left, Right, Middle, Back, Forward)
             0x05.b, 0x09.b,          //     Usage Page: Buttons
             0x19.b, 0x01.b,          //     Usage Minimum: Button 1 (Left)
             0x29.b, 0x05.b,          //     Usage Maximum: Button 5 (Forward)
             0x15.b, 0x00.b,          //     Logical Minimum: 0
             0x25.b, 0x01.b,          //     Logical Maximum: 1
             0x75.b, 0x01.b,          //     Report Size: 1 bit
             0x95.b, 0x05.b,          //     Report Count: 5
             0x81.b, 0x02.b,          //     Input: Data, Variable, Absolute
             // 3-bit constant padding to align to a full byte
             0x75.b, 0x03.b,          //     Report Size: 3 bits
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
             // Scroll wheel (vertical)
             0x09.b, 0x38.b,          //     Usage: Wheel
             0x15.b, 0x81.b,          //     Logical Minimum: -127
             0x25.b, 0x7F.b,          //     Logical Maximum:  127
             0x75.b, 0x08.b,          //     Report Size: 8 bits
             0x95.b, 0x01.b,          //     Report Count: 1
             0x81.b, 0x06.b,          //     Input: Data, Variable, Relative
             // AC Pan (horizontal scroll) — Consumer page
             0x05.b, 0x0C.b,          //     Usage Page: Consumer
             0x0A.b, 0x38.b, 0x02.b,  //     Usage: AC Pan (0x0238)
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
