package com.inputbridge.bridge.ui

import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.model.InputEvent
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.bt.BluetoothHidTransport
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transport adapters for PointerCaptureTrackpadView.
 * Provides zero-overhead direct calls to underlying transports.
 */

/**
 * Bluetooth HID transport adapter.
 * Direct calls to BluetoothHidTransport.sendInputEvent - no coroutine overhead.
 */
class BluetoothHidTrackpadAdapter(
    private val hidTransport: BluetoothHidTransport
) : PointerCaptureTrackpadView.TrackpadTransport {

    private val reportBuilder = com.inputbridge.transport.bt.HidReportBuilder()

    override fun onCursorMove(x: Float, y: Float) {
        // Absolute cursor position - use CursorGoto equivalent
        // For HID, we send relative moves from current position
        // But since we have absolute coords, we'd need to track delta
        // For now, HID mode uses relative moves from the trackpad
    }

    override fun onButtonDown(button: Int) {
        val hidButton = when (button) {
            0 -> com.inputbridge.core.model.MouseButton.LEFT
            1 -> com.inputbridge.core.model.MouseButton.RIGHT
            2 -> com.inputbridge.core.model.MouseButton.MIDDLE
            3 -> com.inputbridge.core.model.MouseButton.BACK
            4 -> com.inputbridge.core.model.MouseButton.FORWARD
            else -> com.inputbridge.core.model.MouseButton.LEFT
        }
        val report = com.inputbridge.transport.bt.HidReportBuilder().onMouseButtonDown(hidButton)
        hidTransport.sendReport(report)
    }

    override fun onButtonUp(button: Int) {
        val hidButton = when (button) {
            0 -> com.inputbridge.core.model.MouseButton.LEFT
            1 -> com.inputbridge.core.model.MouseButton.RIGHT
            2 -> com.inputbridge.core.model.MouseButton.MIDDLE
            3 -> com.inputbridge.core.model.MouseButton.BACK
            4 -> com.inputbridge.core.model.MouseButton.FORWARD
            else -> com.inputbridge.core.model.MouseButton.LEFT
        }
        val report = com.inputbridge.transport.bt.HidReportBuilder().onMouseButtonUp(hidButton)
        hidTransport.sendReport(report)
    }

    override fun onScroll(x: Float, y: Float) {
        val report = com.inputbridge.transport.bt.HidReportBuilder().onScroll(x, y)
        hidTransport.sendReport(report)
    }

    fun onCursorMoveAbsolute(x: Float, y: Float) {
        // For HID, we can't easily do absolute positioning
        // This would require tracking previous position and sending deltas
        // Or use a different approach - send CursorGoto via UDP fallback
    }
}

/**
 * WiFi UDP transport adapter.
 * Uses sendDirect for lowest latency - no channel overhead.
 */
class WifiTrackpadAdapter(
    private val udpTransport: UdpTransport,
    private val packetFactory: EventPacketFactory
) : PointerCaptureTrackpadView.TrackpadTransport {

    override fun onCursorMove(x: Float, y: Float) {
        // CursorGoto with absolute coordinates (0..1)
        val packet = packetFactory.createCursorGoto(x, y)
        udpTransport.sendDirect(packet)
    }

    override fun onButtonDown(button: Int) {
        val mouseButton = when (button) {
            0 -> com.inputbridge.core.model.MouseButton.LEFT
            1 -> com.inputbridge.core.model.MouseButton.RIGHT
            2 -> com.inputbridge.core.model.MouseButton.MIDDLE
            3 -> com.inputbridge.core.model.MouseButton.BACK
            4 -> com.inputbridge.core.model.MouseButton.FORWARD
            else -> com.inputbridge.core.model.MouseButton.LEFT
        }
        val packet = packetFactory.createMouseButtonDown(mouseButton)
        udpTransport.sendDirect(packet)
    }

    override fun onButtonUp(button: Int) {
        val mouseButton = when (button) {
            0 -> com.inputbridge.core.model.MouseButton.LEFT
            1 -> com.inputbridge.core.model.MouseButton.RIGHT
            2 -> com.inputbridge.core.model.MouseButton.MIDDLE
            3 -> com.inputbridge.core.model.MouseButton.BACK
            4 -> com.inputbridge.core.model.MouseButton.FORWARD
            else -> com.inputbridge.core.model.MouseButton.LEFT
        }
        val packet = packetFactory.createMouseButtonUp(mouseButton)
        udpTransport.sendDirect(packet)
    }

    override fun onScroll(x: Float, y: Float) {
        val packet = packetFactory.createScroll(x, y)
        udpTransport.sendDirect(packet)
    }
}

/**
 * Unified trackpad transport that can switch between HID and WiFi.
 */
class UnifiedTrackpadTransport(
    private val hidTransport: BluetoothHidTransport?,
    private val udpTransport: UdpTransport?,
    private val packetFactory: EventPacketFactory?
) : PointerCaptureTrackpadView.TrackpadTransport {

    private var useHid = false

    fun setMode(useHid: Boolean) {
        this.useHid = useHid
    }

    override fun onCursorMove(x: Float, y: Float) {
        if (useHid) {
            // HID mode: send relative moves (would need delta tracking)
            // For now, skip - HID doesn't support absolute well
        } else {
            packetFactory?.let { factory ->
                udpTransport?.sendDirect(factory.createCursorGoto(x, y))
            }
        }
    }

    override fun onButtonDown(button: Int) {
        val mouseButton = when (button) {
            0 -> com.inputbridge.core.model.MouseButton.LEFT
            1 -> com.inputbridge.core.model.MouseButton.RIGHT
            2 -> com.inputbridge.core.model.MouseButton.MIDDLE
            3 -> com.inputbridge.core.model.MouseButton.BACK
            4 -> com.inputbridge.core.model.MouseButton.FORWARD
            else -> com.inputbridge.core.model.MouseButton.LEFT
        }

        if (useHid) {
            hidTransport?.let { hid ->
                val report = com.inputbridge.transport.bt.HidReportBuilder().onMouseButtonDown(mouseButton)
                hid.sendReport(report)
            }
        } else {
            packetFactory?.let { factory ->
                udpTransport?.sendDirect(factory.createMouseButtonDown(mouseButton))
            }
        }
    }

    override fun onButtonUp(button: Int) {
        val mouseButton = when (button) {
            0 -> com.inputbridge.core.model.MouseButton.LEFT
            1 -> com.inputbridge.core.model.MouseButton.RIGHT
            2 -> com.inputbridge.core.model.MouseButton.MIDDLE
            3 -> com.inputbridge.core.model.MouseButton.BACK
            4 -> com.inputbridge.core.model.MouseButton.FORWARD
            else -> com.inputbridge.core.model.MouseButton.LEFT
        }

        if (useHid) {
            hidTransport?.let { hid ->
                val report = com.inputbridge.transport.bt.HidReportBuilder().onMouseButtonUp(mouseButton)
                hid.sendReport(report)
            }
        } else {
            packetFactory?.let { factory ->
                udpTransport?.sendDirect(factory.createMouseButtonUp(mouseButton))
            }
        }
    }

    override fun onScroll(x: Float, y: Float) {
        if (useHid) {
            hidTransport?.let { hid ->
                val report = com.inputbridge.transport.bt.HidReportBuilder().onScroll(x, y)
                hid.sendReport(report)
            }
        } else {
            packetFactory?.let { factory ->
                udpTransport?.sendDirect(factory.createScroll(x, y))
            }
        }
    }
}