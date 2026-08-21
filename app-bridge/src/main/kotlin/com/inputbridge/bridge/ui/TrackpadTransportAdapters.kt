package com.inputbridge.bridge.ui

import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.MouseButton
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.bt.BluetoothHidTransport
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

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

    override fun onCursorMove(x: Float, y: Float) {
        // HID doesn't support absolute positioning well
        // Trackpad sends relative moves from current position
        // For now, skip - HID mode uses relative moves
    }

    override fun onButtonDown(button: Int) {
        val hidButton = when (button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            3 -> MouseButton.BACK
            4 -> MouseButton.FORWARD
            else -> MouseButton.LEFT
        }
        val event = InputEvent.MouseButtonDown(hidButton)
        hidTransport.sendInputEvent(event)
    }

    override fun onButtonUp(button: Int) {
        val hidButton = when (button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            3 -> MouseButton.BACK
            4 -> MouseButton.FORWARD
            else -> MouseButton.LEFT
        }
        val event = InputEvent.MouseButtonUp(hidButton)
        hidTransport.sendInputEvent(event)
    }

    override fun onScroll(x: Float, y: Float) {
        val event = InputEvent.Scroll(x, y)
        hidTransport.sendInputEvent(event)
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
        val event = InputEvent.CursorGoto(x, y)
        val packet = packetFactory.fromEvent(event) ?: return
        udpTransport.sendDirect(packet)
    }

    override fun onButtonDown(button: Int) {
        val mouseButton = when (button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            3 -> MouseButton.BACK
            4 -> MouseButton.FORWARD
            else -> MouseButton.LEFT
        }
        val event = InputEvent.MouseButtonDown(mouseButton)
        val packet = packetFactory.fromEvent(event) ?: return
        udpTransport.sendDirect(packet)
    }

    override fun onButtonUp(button: Int) {
        val mouseButton = when (button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            3 -> MouseButton.BACK
            4 -> MouseButton.FORWARD
            else -> MouseButton.LEFT
        }
        val event = InputEvent.MouseButtonUp(mouseButton)
        val packet = packetFactory.fromEvent(event) ?: return
        udpTransport.sendDirect(packet)
    }

    override fun onScroll(x: Float, y: Float) {
        val event = InputEvent.Scroll(x, y)
        val packet = packetFactory.fromEvent(event) ?: return
        udpTransport.sendDirect(packet)
    }
}

/**
 * Unified trackpad transport that can switch between HID and WiFi.
 */
class UnifiedTrackpadTransport(
    hidTransport: BluetoothHidTransport?,
    udpTransport: UdpTransport?,
    packetFactory: EventPacketFactory?
) : PointerCaptureTrackpadView.TrackpadTransport {

    private var useHid = false

    var hidTransport: BluetoothHidTransport? = hidTransport
    var udpTransport: UdpTransport? = udpTransport
    var packetFactory: EventPacketFactory? = packetFactory

    fun setMode(useHid: Boolean) {
        this.useHid = useHid
    }

    override fun onCursorMove(x: Float, y: Float) {
        if (useHid) {
            // HID mode: send relative moves (would need delta tracking)
            // For now, skip - HID doesn't support absolute well
        } else {
            packetFactory?.let { factory ->
                val event = InputEvent.CursorGoto(x, y)
                val packet = factory.fromEvent(event) ?: return
                udpTransport?.sendDirect(packet)
            }
        }
    }

    override fun onMouseMoveRelative(dx: Float, dy: Float) {
        val event = InputEvent.MouseMove(dx, dy)
        if (useHid) {
            hidTransport?.let { hid ->
                try { hid.sendInputEvent(event) } catch (e: Exception) { Log.e("TrackpadTransport", "HID move failed", e) }
            }
        } else {
            packetFactory?.let { factory ->
                val packet = factory.fromEvent(event) ?: return
                try { udpTransport?.sendDirect(packet) } catch (e: Exception) { Log.e("TrackpadTransport", "WiFi move failed", e) }
            }
        }
    }

    override fun onButtonDown(button: Int) {
        val mouseButton = when (button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            3 -> MouseButton.BACK
            4 -> MouseButton.FORWARD
            else -> MouseButton.LEFT
        }

        if (useHid) {
            hidTransport?.let { hid ->
                try {
                    val event = InputEvent.MouseButtonDown(mouseButton)
                    hid.sendInputEvent(event)
                } catch (e: Exception) {
                    Log.e("TrackpadTransport", "HID button down failed", e)
                }
            }
        } else {
            packetFactory?.let { factory ->
                val event = InputEvent.MouseButtonDown(mouseButton)
                val packet = factory.fromEvent(event) ?: return
                try {
                    udpTransport?.sendDirect(packet)
                } catch (e: Exception) {
                    Log.e("TrackpadTransport", "WiFi button down failed", e)
                }
            }
        }
    }

    override fun onButtonUp(button: Int) {
        val mouseButton = when (button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            3 -> MouseButton.BACK
            4 -> MouseButton.FORWARD
            else -> MouseButton.LEFT
        }

        if (useHid) {
            hidTransport?.let { hid ->
                try {
                    val event = InputEvent.MouseButtonUp(mouseButton)
                    hid.sendInputEvent(event)
                } catch (e: Exception) {
                    Log.e("TrackpadTransport", "HID button up failed", e)
                }
            }
        } else {
            packetFactory?.let { factory ->
                val event = InputEvent.MouseButtonUp(mouseButton)
                val packet = factory.fromEvent(event) ?: return
                try {
                    udpTransport?.sendDirect(packet)
                } catch (e: Exception) {
                    Log.e("TrackpadTransport", "WiFi button up failed", e)
                }
            }
        }
    }

    override fun onScroll(x: Float, y: Float) {
        if (useHid) {
            hidTransport?.let { hid ->
                try {
                    val event = InputEvent.Scroll(x, y)
                    hid.sendInputEvent(event)
                } catch (e: Exception) {
                    Log.e("TrackpadTransport", "HID scroll failed", e)
                }
            }
        } else {
            packetFactory?.let { factory ->
                val event = InputEvent.Scroll(x, y)
                val packet = factory.fromEvent(event) ?: return
                try {
                    udpTransport?.sendDirect(packet)
                } catch (e: Exception) {
                    Log.e("TrackpadTransport", "WiFi scroll failed", e)
                }
            }
        }
    }
}