package com.inputbridge.input

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "UsbInputCapture"

/**
 * Captures keyboard and mouse input from the Portronics Key2 Combo USB receiver
 * connected to the Redmi 9 via OTG.
 *
 * The receiver appears as a standard USB HID device with up to two interfaces:
 * one for the keyboard and one for the mouse.  Both are polled from a background
 * coroutine per interface.
 *
 * Interface detection strategy (in priority order):
 * 1. interfaceSubclass == 1 (BOOT) + interfaceProtocol == 1  → keyboard
 * 2. interfaceSubclass == 1 (BOOT) + interfaceProtocol == 2  → mouse
 * 3. interfaceProtocol == 1 (regardless of subclass)          → keyboard
 * 4. interfaceProtocol == 2 (regardless of subclass)          → mouse
 * 5. maxPacketSize ≤ 8 with ≤5 bytes actually transferred     → mouse (small = mouse report)
 * 6. maxPacketSize >= 8                                        → keyboard (fallback)
 *
 * This handles combo receivers that advertise subclass=0 / protocol=0 on one or
 * both interfaces, as long as they still send standard boot protocol reports.
 *
 * Mouse report formats handled:
 *   4-byte: [buttons, dx, dy, wheel]
 *   5-byte: [buttons, dx, dy, wheel, ac_pan]   (high-resolution wheel extension)
 *   8-byte: [buttons, dx, dy, wheel, 0, 0, 0, 0] (some receivers pad to keyboard size)
 *
 * Hot path: USB interrupt transfer → parse HID report → emit InputEvent.
 * Nothing on this path touches disk, network, or the main thread.
 */
class UsbInputCapture(
    private val context: Context,
    private val device: UsbDevice,
) : InputCapture {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)

    override val events: Flow<InputEvent> = _events.asSharedFlow()
    override val status: Flow<CaptureStatus> = _status.asStateFlow()
    override var isActive: Boolean = false
        private set

    private var connection: UsbDeviceConnection? = null
    private val captureJobs = mutableListOf<Job>()
    // BUG-048 fix: track every successfully claimed interface so stop() can release them.
    private val claimedInterfaces = mutableListOf<UsbInterface>()

    override suspend fun start(): Boolean {
        if (isActive) return true
        _status.value = CaptureStatus.Starting

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = usbManager.openDevice(device) ?: run {
            BridgeLogger.e(TAG, "Failed to open USB device: ${device.deviceName}")
            _status.value = CaptureStatus.Error("Cannot open USB device", recoverable = false)
            return false
        }
        connection = conn

        var started = false
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            if (!conn.claimInterface(iface, true)) {
                BridgeLogger.w(TAG, "Could not claim HID interface $i — skipping")
                continue
            }
            // BUG-048 fix: record every claimed interface immediately after claim succeeds.
            // We record even before the endpoint check because the interface is already claimed
            // at this point; if the endpoint is absent we continue but the claim still exists
            // and must be released in stop().
            claimedInterfaces += iface
            // BUG-055 fix: `continue` inside `?: run {}` triggers the experimental Kotlin 2.0
            // feature "break/continue in inline lambdas" which is not opted into in this project.
            // Compiler error: "The feature 'break continue in inline lambdas' is experimental and
            // should be enabled explicitly." Use an explicit null check instead — Kotlin smart-
            // casts `endpoint` to non-null for all subsequent uses within this loop iteration.
            val endpoint = findInterruptInEndpoint(iface)
            if (endpoint == null) {
                BridgeLogger.w(TAG, "No interrupt-in endpoint on HID interface $i")
                continue
            }

            val ifaceType = detectInterfaceType(iface, endpoint)
            BridgeLogger.i(
                TAG,
                "HID interface $i: subclass=${iface.interfaceSubclass} " +
                    "protocol=${iface.interfaceProtocol} maxPacket=${endpoint.maxPacketSize} " +
                    "→ $ifaceType"
            )

            val job = when (ifaceType) {
                HidInterfaceType.KEYBOARD -> scope.launch { readKeyboard(conn, iface, endpoint) }
                HidInterfaceType.MOUSE    -> scope.launch { readMouse(conn, iface, endpoint) }
            }
            captureJobs += job
            started = true
        }

        return if (started) {
            isActive = true
            _status.value = CaptureStatus.Active
            true
        } else {
            BridgeLogger.e(TAG, "No usable HID interfaces found on device")
            conn.close()
            _status.value = CaptureStatus.Error("No HID interfaces on device", recoverable = false)
            false
        }
    }

    override suspend fun stop() {
        if (!isActive) return
        isActive = false
        captureJobs.forEach { it.cancel() }
        captureJobs.clear()
        // BUG-048 fix: release all claimed interfaces before closing the connection.
        // On some Android devices/kernel versions, closing without prior releaseInterface()
        // leaves the interface locked until an OS-level timeout, blocking re-open on replug.
        connection?.let { conn ->
            claimedInterfaces.forEach { iface -> conn.releaseInterface(iface) }
        }
        claimedInterfaces.clear()
        connection?.close()
        connection = null
        _status.value = CaptureStatus.Stopped
        BridgeLogger.i(TAG, "USB input capture stopped")
    }

    // ── Interface type detection ──────────────────────────────────────────────

    private enum class HidInterfaceType { KEYBOARD, MOUSE }

    /**
     * Determine whether a HID interface is a keyboard or mouse.
     *
     * Priority:
     *  1. Standard boot protocol subclass (1) + protocol (1=keyboard, 2=mouse)
     *  2. Protocol alone (some devices skip the boot subclass)
     *  3. Heuristic: small max-packet-size → mouse (boot mouse report ≤ 8 bytes,
     *     but keyboard report is exactly 8 bytes — distinguish by protocol)
     *  4. Fallback: assume keyboard (safer default — at least keys will work)
     */
    private fun detectInterfaceType(iface: UsbInterface, endpoint: UsbEndpoint): HidInterfaceType {
        val sub  = iface.interfaceSubclass
        val prot = iface.interfaceProtocol

        // Standard HID boot protocol class
        if (sub == SUBCLASS_BOOT) {
            if (prot == PROTOCOL_KEYBOARD) return HidInterfaceType.KEYBOARD
            if (prot == PROTOCOL_MOUSE)    return HidInterfaceType.MOUSE
        }
        // Protocol hint even with non-standard subclass
        if (prot == PROTOCOL_KEYBOARD) return HidInterfaceType.KEYBOARD
        if (prot == PROTOCOL_MOUSE)    return HidInterfaceType.MOUSE

        // Heuristic: mouse boot report is 3–5 bytes; keyboard is 8 bytes.
        // Use maxPacketSize ≤ 6 as a mouse indicator only when maxPacket is small
        // (avoids misidentifying an 8-byte keyboard interface as keyboard by default).
        if (endpoint.maxPacketSize in 3..6) return HidInterfaceType.MOUSE

        // Default: treat as keyboard (best fallback — at least key events flow)
        return HidInterfaceType.KEYBOARD
    }

    // ── Keyboard reader ───────────────────────────────────────────────────────

    private suspend fun readKeyboard(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        endpoint: UsbEndpoint,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(endpoint.maxPacketSize.coerceAtLeast(8))
        var prevKeys = IntArray(6)
        var prevModifiers: ModifierState = ModifierState.NONE

        while (this@UsbInputCapture.isActive && coroutineContext.isActive) {
            val transferred = conn.bulkTransfer(endpoint, buf, buf.size, TRANSFER_TIMEOUT_MS)
            if (transferred < 2) continue  // need at least modifier byte + reserved

            val modByte = buf[0]
            val modifiers = parseModifiers(modByte)
            if (modifiers != prevModifiers) {
                _events.emit(InputEvent.ModifierStateChanged(modifiers))
                prevModifiers = modifiers
            }

            // Keys are in bytes 2–7 (byte 1 is reserved = 0x00 in boot protocol)
            val keyCount = (transferred - 2).coerceIn(0, 6)
            val currentKeys = IntArray(keyCount) { buf[2 + it].toInt() and 0xFF }

            // Key-up events for keys no longer pressed
            for (prev in prevKeys) {
                if (prev != 0 && prev !in currentKeys) {
                    val androidCode = KeyMap.hidToAndroid(prev)
                    _events.emit(InputEvent.KeyUp(androidCode, prev, modifiers))
                }
            }
            // Key-down events for newly pressed keys
            for (curr in currentKeys) {
                if (curr != 0 && curr !in prevKeys) {
                    val androidCode = KeyMap.hidToAndroid(curr)
                    _events.emit(InputEvent.KeyDown(androidCode, curr, modifiers))
                }
            }
            // Pad prevKeys to 6 slots so the "not in" check works correctly next iteration
            prevKeys = IntArray(6).also { dst -> currentKeys.copyInto(dst) }
        }
    }

    // ── Mouse reader ──────────────────────────────────────────────────────────

    /**
     * Parse mouse HID boot protocol reports.
     *
     * Supported report layouts:
     *  3-byte: [buttons, dx, dy]                   — minimal boot mouse
     *  4-byte: [buttons, dx, dy, wheel]             — standard boot mouse
     *  5-byte: [buttons, dx, dy, wheel, ac_pan]     — extended (tilt wheel / panning)
     *  8-byte: [buttons, dx, dy, wheel, 0, 0, 0, 0] — padded to match keyboard size
     *
     * All delta bytes are signed (two's complement); cast through toByte() to preserve sign.
     */
    private suspend fun readMouse(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        endpoint: UsbEndpoint,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(endpoint.maxPacketSize.coerceAtLeast(8))
        var prevButtons = 0

        while (this@UsbInputCapture.isActive && coroutineContext.isActive) {
            val transferred = conn.bulkTransfer(endpoint, buf, buf.size, TRANSFER_TIMEOUT_MS)
            if (transferred < 3) continue

            val buttons = buf[0].toInt() and 0x07   // bits 0–2: left, right, middle
            val dx      = buf[1].toByte().toFloat()  // signed relative X
            val dy      = buf[2].toByte().toFloat()  // signed relative Y
            // Wheel (byte 3) present in 4-byte and 5-byte reports
            val wheel   = if (transferred >= 4) buf[3].toByte().toFloat() else 0f

            // Mouse movement — emit if either axis has a delta
            if (dx != 0f || dy != 0f) {
                _events.emit(InputEvent.MouseMove(dx, dy))
            }

            // Scroll wheel — invert so physical wheel-down scrolls content up
            // (positive wheel from USB = scroll up; InputBridge Scroll.dy > 0 = content moves up)
            if (wheel != 0f) {
                _events.emit(InputEvent.Scroll(0f, -wheel))
            }

            // Button state changes (bits 0=left, 1=right, 2=middle)
            for (bit in 0..2) {
                val mask    = 1 shl bit
                val wasDown = (prevButtons and mask) != 0
                val isDown  = (buttons    and mask) != 0
                val button  = MouseButton.fromId(bit.toByte())
                if (!wasDown && isDown) _events.emit(InputEvent.MouseButtonDown(button))
                if (wasDown  && !isDown) _events.emit(InputEvent.MouseButtonUp(button))
            }
            prevButtons = buttons
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findInterruptInEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                ep.direction == UsbConstants.USB_DIR_IN) {
                return ep
            }
        }
        return null
    }

    private fun parseModifiers(byte: Byte): ModifierState {
        val b = byte.toInt() and 0xFF
        return ModifierState(
            shift    = (b and 0x22) != 0,  // bit 1 = Left Shift, bit 5 = Right Shift
            ctrl     = (b and 0x11) != 0,  // bit 0 = Left Ctrl,  bit 4 = Right Ctrl
            alt      = (b and 0x44) != 0,  // bit 2 = Left Alt,   bit 6 = Right Alt
            meta     = (b and 0x88) != 0,  // bit 3 = Left GUI,   bit 7 = Right GUI
            capsLock = false,               // tracked via KeyDown CAPS_LOCK events
        )
    }

    companion object {
        private const val TRANSFER_TIMEOUT_MS = 50  // short timeout keeps the loop responsive

        // HID Boot Interface Subclass and Protocol constants
        private const val SUBCLASS_BOOT      = 1
        private const val PROTOCOL_KEYBOARD  = 1
        private const val PROTOCOL_MOUSE     = 2

        /** Check if any known HID devices are accessible on this USB Manager. */
        fun findHidDevices(context: Context): List<UsbDevice> {
            val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return mgr.deviceList.values.filter { device ->
                (0 until device.interfaceCount).any { i ->
                    device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID
                }
            }
        }
    }
}
