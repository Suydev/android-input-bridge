package com.inputbridge.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.ModifierState
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

private const val TAG = "BridgeA11y"

/**
 * AccessibilityService on the bridge phone that captures USB keyboard input
 * via onKeyEvent() and forwards it to the receiver over UDP.
 *
 * This works because Android routes ALL key events (including from USB HID
 * keyboards) through the accessibility framework before delivering them to
 * apps. Even though UsbManager.deviceList blacklists boot HID devices, the
 * InputDispatcher still injects key events that accessibility services can
 * intercept with FLAG_REQUEST_FILTER_KEY_EVENTS.
 *
 * Lifecycle:
 * - onServiceConnected(): sets up flag, opens UDP transport
 * - onKeyEvent(): converts KeyEvent → InputEvent → Packet → UDP send
 * - onUnbind(): disconnects transport
 */
class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }

    private val packetFactory = EventPacketFactory()
    private var udpTransport: UdpTransport? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: BridgePreferences by lazy {
        BridgePreferences(applicationContext)
    }

    // Modifier state tracking
    @Volatile private var shiftPressed = false
    @Volatile private var ctrlPressed = false
    @Volatile private var altPressed = false
    @Volatile private var metaPressed = false

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BridgeLogger.i(TAG, "Bridge accessibility service connected")

        // Request key event filtering
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        connectTransport()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { udpTransport?.send(packetFactory.makeDisconnect()) }
                delay(50L)
                runCatching { udpTransport?.disconnect() }
            }
        }
        BridgeLogger.i(TAG, "Bridge accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need onKeyEvent()
    }

    override fun onInterrupt() {
        BridgeLogger.w(TAG, "Bridge accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Key event capture ───────────────────────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val transport = udpTransport
        if (transport == null || !transport.isConnected) {
            // Don't consume — let the system handle it normally
            return false
        }

        // Update modifier state
        when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                shiftPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                ctrlPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT ->
                altPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT ->
                metaPressed = event.action == KeyEvent.ACTION_DOWN
        }

        val modifiers = ModifierState(
            shift = shiftPressed,
            ctrl = ctrlPressed,
            alt = altPressed,
            meta = metaPressed,
        )

        val inputEvent = if (event.action == KeyEvent.ACTION_DOWN) {
            InputEvent.KeyDown(
                keyCode = event.keyCode,
                scanCode = event.scanCode,
                modifiers = modifiers,
            )
        } else if (event.action == KeyEvent.ACTION_UP) {
            InputEvent.KeyUp(
                keyCode = event.keyCode,
                scanCode = event.scanCode,
                modifiers = modifiers,
            )
        } else {
            return false
        }

        val packet = packetFactory.fromEvent(inputEvent) ?: return false
        scope.launch { transport.send(packet) }

        // Log first few events for debugging
        BridgeLogger.d(TAG, "Key event: ${KeyEvent.keyCodeToString(event.keyCode)} " +
            "action=${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}")

        // Consume the event so it doesn't also reach local apps
        // (the user wants it forwarded to the tablet, not processed locally)
        return true
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private fun connectTransport() {
        val targetIp = prefs.targetIp
        val port = prefs.port

        if (targetIp.isBlank()) {
            BridgeLogger.w(TAG, "Target IP not configured — keyboard forwarding disabled")
            return
        }

        scope.launch {
            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            if (ok) {
                udpTransport = transport
                BridgeLogger.i(TAG, "Keyboard UDP transport connected → $targetIp:$port")
            } else {
                BridgeLogger.e(TAG, "Keyboard UDP transport failed to connect")
            }
        }
    }
}
