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
 * one for the keyboard and one for the mouse. Both are polled from a single
 * background coroutine per interface.
 *
 * Hot path: USB interrupt transfer → parse HID report → emit InputEvent.
 * Nothing on this path touches disk, network, or the main thread.
 *
 * HID report format (standard boot protocol):
 *   Keyboard (8 bytes): [modifiers, reserved, key1, key2, key3, key4, key5, key6]
 *   Mouse (4 bytes):    [buttons, dx, dy, wheel]
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
                BridgeLogger.w(TAG, "Could not claim HID interface $i")
                continue
            }
            val endpoint = findInterruptInEndpoint(iface) ?: continue
            BridgeLogger.i(TAG, "Starting capture on HID interface $i (${iface.interfaceSubclass})")

            val job = when (iface.interfaceSubclass) {
                SUBCLASS_KEYBOARD -> scope.launch { readKeyboard(conn, iface, endpoint) }
                SUBCLASS_MOUSE    -> scope.launch { readMouse(conn, iface, endpoint) }
                else              -> scope.launch { readGenericHid(conn, iface, endpoint) }
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
        connection?.close()
        connection = null
        _status.value = CaptureStatus.Stopped
        BridgeLogger.i(TAG, "USB input capture stopped")
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
            if (transferred < 8) continue

            val modByte = buf[0]
            val modifiers = parseModifiers(modByte)
            if (modifiers != prevModifiers) {
                _events.emit(InputEvent.ModifierStateChanged(modifiers))
                prevModifiers = modifiers
            }

            val currentKeys = IntArray(6) { buf[2 + it].toInt() and 0xFF }

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
            prevKeys = currentKeys
        }
    }

    // ── Mouse reader ──────────────────────────────────────────────────────────

    private suspend fun readMouse(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        endpoint: UsbEndpoint,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(endpoint.maxPacketSize.coerceAtLeast(4))
        var prevButtons = 0

        while (this@UsbInputCapture.isActive && coroutineContext.isActive) {
            val transferred = conn.bulkTransfer(endpoint, buf, buf.size, TRANSFER_TIMEOUT_MS)
            if (transferred < 3) continue

            val buttons = buf[0].toInt() and 0x07
            val dx = buf[1].toInt().toByte().toFloat()  // signed
            val dy = buf[2].toInt().toByte().toFloat()  // signed
            val wheel = if (transferred >= 4) buf[3].toInt().toByte().toFloat() else 0f

            // Mouse movement
            if (dx != 0f || dy != 0f) {
                _events.emit(InputEvent.MouseMove(dx, dy))
            }

            // Scroll
            if (wheel != 0f) {
                _events.emit(InputEvent.Scroll(0f, -wheel)) // invert: wheel down = scroll up content
            }

            // Button changes
            for (bit in 0..2) {
                val mask = 1 shl bit
                val wasDown = (prevButtons and mask) != 0
                val isDown  = (buttons   and mask) != 0
                val button  = MouseButton.fromId(bit.toByte())
                if (!wasDown && isDown) _events.emit(InputEvent.MouseButtonDown(button))
                if (wasDown && !isDown) _events.emit(InputEvent.MouseButtonUp(button))
            }
            prevButtons = buttons
        }
    }

    private suspend fun readGenericHid(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        endpoint: UsbEndpoint,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(endpoint.maxPacketSize.coerceAtLeast(8))
        while (this@UsbInputCapture.isActive && coroutineContext.isActive) {
            conn.bulkTransfer(endpoint, buf, buf.size, TRANSFER_TIMEOUT_MS)
            // Generic HID — no-op for now; extend in future phases
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
            shift    = (b and 0x22) != 0, // left or right shift
            ctrl     = (b and 0x11) != 0,
            alt      = (b and 0x44) != 0,
            meta     = (b and 0x88) != 0,
            capsLock = false, // tracked via key events
        )
    }

    companion object {
        private const val TRANSFER_TIMEOUT_MS = 50      // short timeout keeps the loop responsive
        private const val SUBCLASS_KEYBOARD = 1
        private const val SUBCLASS_MOUSE    = 2

        /** Check if all known HID devices on this Android USB Manager are accessible. */
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
