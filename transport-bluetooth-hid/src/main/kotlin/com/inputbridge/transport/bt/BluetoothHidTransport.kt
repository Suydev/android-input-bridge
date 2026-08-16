package com.inputbridge.transport.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.inputbridge.core.config.FeatureFlags
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.protocol.Packet
import com.inputbridge.transport.wifi.ConnectionState
import com.inputbridge.transport.wifi.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

private const val TAG = "BluetoothHidTransport"

private const val REGISTER_TIMEOUT_MS = 10_000L
private const val CONNECT_TIMEOUT_MS  = 15_000L
private const val RECONNECT_DELAY_MS  = 2_000L
private const val RECONNECT_MAX_ATTEMPTS = 5

/**
 * Bluetooth HID Device transport — Phase 6.
 *
 * Registers the bridge phone as a Bluetooth HID combo keyboard + mouse.
 * Any Bluetooth host (tablet, PC, phone, smart TV…) that connects to it
 * receives a real hardware-level cursor and keyboard — no root, no ADB,
 * no receiver app required on the host side.
 *
 * Improvements over original:
 * - BroadcastReceiver for BT lifecycle events (adapter state, ACL disconnect)
 * - Automatic reconnection on BT drop (up to RECONNECT_MAX_ATTEMPTS)
 * - Re-acquire HID profile proxy when service disconnects
 * - Connection priority request after successful connection
 */
class BluetoothHidTransport(private val context: Context) : Transport {

    // ── Transport interface state ──────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)

    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()
    override val incomingPackets: Flow<Packet>          = _incomingPackets.asSharedFlow()

    /** True only when a Bluetooth host is actively connected and accepting reports. */
    override val isConnected: Boolean get() = connectedHost != null

    // ── Internal state ────────────────────────────────────────────────────────

    @Volatile private var hidDevice: BluetoothHidDevice? = null

    /** The currently connected host device (null = no host). */
    @Volatile private var connectedHost: BluetoothDevice? = null

    /**
     * Bluetooth MAC address of the host to connect to (e.g. "A1:B2:C3:D4:E5:F6").
     * Leave blank to register as a HID device and wait for the host to connect.
     */
    @Volatile var targetDeviceAddress: String = ""

    private val reportBuilder = HidReportBuilder()

    /** BUG-XXX FIX: mutable so reacquireProxy() can replace it with a fresh instance. */
    private var appRegistered = CompletableDeferred<Boolean>()

    /** Completed once the target host device connects (or times out). */
    private var connectionDeferred = CompletableDeferred<Boolean>()

    /** Scope for reconnection coroutines. */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Reconnection job — non-null while a reconnect attempt is in progress. */
    private var reconnectJob: Job? = null

    /** BUG-XXX: single executor reused across registerApp() calls to avoid thread leak. */
    private val registerExecutor = Executors.newSingleThreadExecutor()

    /** Number of consecutive reconnection attempts (reset on successful connect). */
    @Volatile private var reconnectAttempts = 0

    /** True while the transport is in "keep alive" mode (should auto-reconnect). */
    @Volatile private var keepAlive = false

    // ── BluetoothHidDevice.Callback ───────────────────────────────────────────

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            BridgeLogger.i(TAG, "HID app status: registered=$registered pluggedDevice=$pluggedDevice")
            if (!appRegistered.isCompleted) appRegistered.complete(registered)
            // pluggedDevice != null means a host was already connected when we registered
            if (registered && pluggedDevice != null) {
                handleHostConnected(pluggedDevice)
                if (!connectionDeferred.isCompleted) connectionDeferred.complete(true)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            BridgeLogger.i(TAG, "BT HID state: ${deviceLabel(device)} → $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.Connecting
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    handleHostConnected(device)
                    reconnectAttempts = 0
                    if (!connectionDeferred.isCompleted) connectionDeferred.complete(true)
                }
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedHost = null
                    _connectionState.value = ConnectionState.Disconnected
                    DiagnosticsManager.update { copy(btConnected = false, btDeviceName = "") }
                    BridgeLogger.i(TAG, "BT HID host disconnected: ${deviceLabel(device)}")
                    // Auto-reconnect if we should stay alive
                    if (keepAlive) scheduleReconnect()
                }
            }
        }

        /** Host requests a report — reply with an empty report (we are output-only). */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, ByteArray(0))
        }

        /** Host sends SET_REPORT (e.g. Caps-Lock LED state). Log and ignore for now. */
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            BridgeLogger.d(TAG, "SET_REPORT id=0x${id.toInt().and(0xFF).toString(16)} data=${data.hex()}")
        }
    }

    // ── BluetoothProfile.ServiceListener ─────────────────────────────────────

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            BridgeLogger.i(TAG, "HID_DEVICE profile proxy connected — registering app")
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            BridgeLogger.w(TAG, "HID_DEVICE profile proxy disconnected — re-acquiring")
            hidDevice = null
            connectedHost = null
            _connectionState.value = ConnectionState.Disconnected
            // Re-acquire the proxy — the BT service may have restarted
            if (keepAlive) reacquireProxy()
        }
    }

    // ── BroadcastReceiver for BT lifecycle events ─────────────────────────────

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                    BridgeLogger.i(TAG, "BT adapter state changed: $state")
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            // Bluetooth turned off — clean up
                            connectedHost = null
                            hidDevice = null
                            _connectionState.value = ConnectionState.Error("Bluetooth turned off")
                            DiagnosticsManager.update { copy(btConnected = false, btDeviceName = "") }
                        }
                        BluetoothAdapter.STATE_ON -> {
                            // Bluetooth turned back on — re-acquire proxy and reconnect
                            BridgeLogger.i(TAG, "BT turned on — re-acquiring HID proxy")
                            if (keepAlive) reacquireProxy()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && device == connectedHost) {
                        BridgeLogger.w(TAG, "ACL disconnected for host: ${deviceLabel(device)}")
                        connectedHost = null
                        _connectionState.value = ConnectionState.Disconnected
                        DiagnosticsManager.update { copy(btConnected = false, btDeviceName = "") }
                        if (keepAlive) scheduleReconnect()
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        BridgeLogger.i(TAG, "ACL connected: ${deviceLabel(device)}")
                    }
                }
            }
        }
    }

    private var receiverRegistered = false

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Send an [InputEvent] as a Bluetooth HID report to the connected host.
     *
     * Hot path — must not block or allocate unnecessarily.
     * Returns false if no host is connected or the report could not be sent.
     */
    fun sendInputEvent(event: InputEvent): Boolean {
        val hid  = hidDevice    ?: return false
        val host = connectedHost ?: return false
        return try {
            when (event) {
                is InputEvent.KeyDown         -> hid.sendReport(host, HidDescriptor.REPORT_ID_KEYBOARD, reportBuilder.onKeyDown(event))
                is InputEvent.KeyUp           -> hid.sendReport(host, HidDescriptor.REPORT_ID_KEYBOARD, reportBuilder.onKeyUp(event))
                is InputEvent.MouseMove       -> hid.sendReport(host, HidDescriptor.REPORT_ID_MOUSE,    reportBuilder.onMouseMove(event.dx, event.dy))
                is InputEvent.MouseButtonDown -> hid.sendReport(host, HidDescriptor.REPORT_ID_MOUSE,    reportBuilder.onMouseButtonDown(event.button))
                is InputEvent.MouseButtonUp   -> hid.sendReport(host, HidDescriptor.REPORT_ID_MOUSE,    reportBuilder.onMouseButtonUp(event.button))
                is InputEvent.Scroll          -> hid.sendReport(host, HidDescriptor.REPORT_ID_MOUSE,    reportBuilder.onScroll(event.dy))
                // TextInput, ModifierStateChanged, NavigationAction, CursorGoto — not forwarded
                // via BT HID; they are handled at the accessibility layer on devices that
                // have the receiver app.
                is InputEvent.TextInput,
                is InputEvent.ModifierStateChanged,
                is InputEvent.NavigationAction,
                is InputEvent.CursorGoto -> true
            }
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "sendInputEvent failed: ${e.message}")
            false
        }
    }

    // ── Transport interface ────────────────────────────────────────────────────

    /**
     * Register as a Bluetooth HID device and optionally connect to [targetDeviceAddress].
     *
     * Returns true if:
     *   - The HID app was registered successfully, AND
     *   - Either no target address is set (we are ready and waiting for the host),
     *     OR the connection to the target host was confirmed.
     *
     * Returns false on any unrecoverable failure (Bluetooth off, HID Device role
     * not supported, registration timeout, connection timeout). The caller should
     * fall back to UDP transport in that case.
     */
    override suspend fun connect(): Boolean {
        if (!FeatureFlags.BLUETOOTH_HID_ENABLED) {
            BridgeLogger.i(TAG, "BT HID disabled by feature flag")
            return false
        }

        val adapter = getAdapter() ?: run {
            BridgeLogger.w(TAG, "Bluetooth not available on this device")
            _connectionState.value = ConnectionState.Error("Bluetooth unavailable")
            return false
        }

        if (!adapter.isEnabled) {
            BridgeLogger.w(TAG, "Bluetooth is off — user must enable it")
            _connectionState.value = ConnectionState.Error("Bluetooth is off — enable it in Settings")
            return false
        }

        keepAlive = true
        _connectionState.value = ConnectionState.Connecting

        // Register BroadcastReceiver for BT lifecycle events
        registerReceiver()

        // Request the HID_DEVICE profile proxy.  profileListener.onServiceConnected()
        // fires asynchronously; it calls registerHidApp() when ready.
        val profileOk = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (!profileOk) {
            BridgeLogger.e(TAG, "getProfileProxy(HID_DEVICE) returned false — HID Device role not supported on this phone")
            _connectionState.value = ConnectionState.Error("Bluetooth HID Device role not supported — try UDP mode")
            return false
        }

        // Wait for registerHidApp() → hidCallback.onAppStatusChanged()
        val registered = withTimeoutOrNull(REGISTER_TIMEOUT_MS) { appRegistered.await() } ?: false
        if (!registered) {
            BridgeLogger.e(TAG, "HID app registration failed or timed out after ${REGISTER_TIMEOUT_MS}ms")
            _connectionState.value = ConnectionState.Error("HID app registration failed")
            return false
        }

        BridgeLogger.i(TAG, "HID app registered successfully")

        if (targetDeviceAddress.isNotBlank()) {
            return connectToHost(adapter)
        }

        // No target address — registered and advertising; wait for host to initiate.
        _connectionState.value = ConnectionState.Connected  // "ready" — host not yet connected
        BridgeLogger.i(TAG, "HID registered — no target address set, waiting for any host to connect")
        return true
    }

    override suspend fun disconnect() {
        keepAlive = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0

        try {
            // Release all keys on the host before disconnecting (prevents stuck keys)
            connectedHost?.let { host ->
                runCatching {
                    hidDevice?.sendReport(host, HidDescriptor.REPORT_ID_KEYBOARD, reportBuilder.buildAllRelease())
                }
            }
            connectedHost?.let { hidDevice?.disconnect(it) }
            hidDevice?.unregisterApp()
            getAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "Error during disconnect: ${e.message}")
        } finally {
            hidDevice      = null
            connectedHost  = null
            _connectionState.value = ConnectionState.Disconnected
            DiagnosticsManager.update { copy(btConnected = false, btDeviceName = "") }
            BridgeLogger.i(TAG, "BluetoothHidTransport disconnected")
        }

        unregisterReceiver()
    }

    /**
     * Not used in BT HID mode — input events bypass the Packet serialization layer
     * and go directly through [sendInputEvent]. Always returns false.
     */
    override suspend fun send(packet: Packet): Boolean = false

    // ── Reconnection logic ────────────────────────────────────────────────────

    /**
     * Schedule a reconnection attempt with exponential backoff.
     * Called when the BT connection drops unexpectedly.
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            BridgeLogger.e(TAG, "BT reconnect failed after $RECONNECT_MAX_ATTEMPTS attempts — giving up")
            _connectionState.value = ConnectionState.Error("Bluetooth connection lost — tap MOUSE to reconnect")
            return
        }

        val delayMs = RECONNECT_DELAY_MS * (reconnectAttempts + 1)
        BridgeLogger.i(TAG, "Scheduling BT reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1}/$RECONNECT_MAX_ATTEMPTS)")

        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectAttempts++
            reacquireProxy()
        }
    }

    /**
     * Re-acquire the HID_DEVICE profile proxy and re-register the HID app.
     * This handles cases where the BT service restarted or the proxy became stale.
     */
    private fun reacquireProxy() {
        val adapter = getAdapter() ?: return
        if (!adapter.isEnabled) {
            BridgeLogger.w(TAG, "Cannot re-acquire proxy: BT is off")
            return
        }

        BridgeLogger.i(TAG, "Re-acquiring HID_DEVICE profile proxy")
        // BUG-XXX FIX: replace with a fresh deferred so connect() waits for new registration
        appRegistered = CompletableDeferred()

        // Close old proxy
        hidDevice?.let { getAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null

        // Create fresh profile listener
        val freshListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hidDevice = proxy as BluetoothHidDevice
                BridgeLogger.i(TAG, "HID_DEVICE proxy re-acquired — registering app")
                registerHidApp()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                BridgeLogger.w(TAG, "HID_DEVICE proxy lost again during re-acquire")
                hidDevice = null
            }
        }

        adapter.getProfileProxy(context, freshListener, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Request connection priority to reduce latency and improve resilience.
     * Called after a successful host connection.
     */
    private fun requestConnectionPriority() {
        val hid = hidDevice ?: return
        val host = connectedHost ?: return

        try {
            // Get the GATT connection to request priority (available on API 21+)
            val gatt = hid.javaClass.getMethod("getConnection", BluetoothDevice::class.java)
                .invoke(hid, host)
            if (gatt != null) {
                val requestPriority = gatt.javaClass.getMethod("requestConnectionPriority", Int::class.javaPrimitiveType)
                // CONNECTION_PRIORITY_HIGH = 1
                requestPriority.invoke(gatt, 1)
                BridgeLogger.i(TAG, "Requested HIGH connection priority for ${deviceLabel(host)}")
            }
        } catch (e: Exception) {
            BridgeLogger.d(TAG, "Could not request connection priority: ${e.message}")
        }
    }

    // ── Receiver management ───────────────────────────────────────────────────

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        context.registerReceiver(btReceiver, filter)
        receiverRegistered = true
        BridgeLogger.i(TAG, "BT BroadcastReceiver registered")
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            BridgeLogger.d(TAG, "Receiver already unregistered: ${e.message}")
        }
        receiverRegistered = false
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun registerHidApp() {
        val hid = hidDevice ?: run {
            BridgeLogger.e(TAG, "registerHidApp: hidDevice is null")
            if (!appRegistered.isCompleted) appRegistered.complete(false)
            return
        }

        val sdp = BluetoothHidDeviceAppSdpSettings(
            /* name        */ "InputBridge Keyboard+Mouse",
            /* description */ "USB keyboard and mouse bridge",
            /* provider    */ "InputBridge",
            /* subclass    */ BluetoothHidDevice.SUBCLASS1_COMBO,
            /* descriptors */ HidDescriptor.DESCRIPTOR,
        )

        // BUG-XXX FIX: reuse a single executor to avoid thread leak on reconnection.
        val callOk = hid.registerApp(
            sdp,
            /* qosOut */ null,  // best-effort QoS
            /* qosIn  */ null,
            registerExecutor,
            hidCallback,
        )
        if (!callOk) {
            BridgeLogger.e(TAG, "registerApp() returned false synchronously")
            if (!appRegistered.isCompleted) appRegistered.complete(false)
        }
    }

    private suspend fun connectToHost(adapter: BluetoothAdapter): Boolean {
        val target = runCatching { adapter.getRemoteDevice(targetDeviceAddress) }.getOrNull() ?: run {
            BridgeLogger.e(TAG, "Invalid BT address: '$targetDeviceAddress'")
            _connectionState.value = ConnectionState.Error("Invalid address: $targetDeviceAddress")
            return false
        }

        connectionDeferred = CompletableDeferred()

        // connect() is fire-and-forget; the result arrives via onConnectionStateChanged
        val callOk = hidDevice?.connect(target) ?: false
        BridgeLogger.i(TAG, "Connecting to host ${deviceLabel(target)} (immediate=$callOk)")

        val confirmed = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectionDeferred.await() } ?: false
        if (confirmed) {
            // Request high connection priority after successful connect
            requestConnectionPriority()
        } else {
            BridgeLogger.w(TAG, "Connection to $targetDeviceAddress timed out after ${CONNECT_TIMEOUT_MS}ms")
            _connectionState.value = ConnectionState.Error(
                "Could not reach $targetDeviceAddress — is the device on and already paired?"
            )
        }
        return confirmed
    }

    private fun handleHostConnected(device: BluetoothDevice) {
        connectedHost = device
        _connectionState.value = ConnectionState.Connected
        val label = deviceLabel(device)
        DiagnosticsManager.update { copy(btConnected = true, btDeviceName = label) }
        BridgeLogger.i(TAG, "BT HID host connected: $label")
    }

    /** Resolve adapter via BluetoothManager — works on all Android versions. */
    private fun getAdapter(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private fun deviceLabel(device: BluetoothDevice): String = runCatching {
        "${device.name ?: "Unknown"} (${device.address})"
    }.getOrDefault(device.address)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
