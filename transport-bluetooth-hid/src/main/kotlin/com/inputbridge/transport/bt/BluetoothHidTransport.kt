package com.inputbridge.transport.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.inputbridge.core.config.FeatureFlags
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.protocol.Packet
import com.inputbridge.transport.wifi.ConnectionState
import com.inputbridge.transport.wifi.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

private const val TAG = "BluetoothHidTransport"

private const val REGISTER_TIMEOUT_MS = 10_000L
private const val CONNECT_TIMEOUT_MS  = 15_000L

/**
 * Bluetooth HID Device transport — Phase 6.
 *
 * Registers the bridge phone as a Bluetooth HID combo keyboard + mouse.
 * Any Bluetooth host (tablet, PC, phone, smart TV…) that connects to it
 * receives a real hardware-level cursor and keyboard — no root, no ADB,
 * no receiver app required on the host side.
 *
 * Prerequisites on the host:
 *   1. Pair the host device with the bridge phone via Bluetooth Settings.
 *   2. Optionally note the host's BT MAC address and enter it in Settings
 *      so the bridge initiates the connection rather than waiting.
 *
 * Generic design — works with ANY Bluetooth host, not just the OnePlus Pad Go.
 * Works with any USB HID keyboard/mouse combo as input source (not just
 * the Portronics Key2 Combo). All device-specific details live in Settings.
 *
 * Architecture:
 *   UsbInputCapture → InputEvent
 *         ↓
 *   HidReportBuilder.on*(event) → ByteArray (HID report)
 *         ↓
 *   BluetoothHidDevice.sendReport(host, reportId, data)
 *
 * Fallback: if the phone's Bluetooth stack does not support the HID Device
 * role (rare but possible on some vendor ROMs), [connect] returns false and
 * BridgeService falls back to UDP transport automatically.
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

    /** Completed once registerApp() succeeds or fails. */
    private val appRegistered = CompletableDeferred<Boolean>()

    /** Completed once the target host device connects (or times out). */
    private var connectionDeferred = CompletableDeferred<Boolean>()

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
                    if (!connectionDeferred.isCompleted) connectionDeferred.complete(true)
                }
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedHost = null
                    _connectionState.value = ConnectionState.Disconnected
                    DiagnosticsManager.update { copy(btConnected = false, btDeviceName = "") }
                    BridgeLogger.i(TAG, "BT HID host disconnected: ${deviceLabel(device)}")
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
            hidDevice = null
            connectedHost = null
            _connectionState.value = ConnectionState.Disconnected
            BridgeLogger.w(TAG, "HID_DEVICE profile proxy disconnected")
        }
    }

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
                // TextInput, ModifierStateChanged, NavigationAction — not forwarded via BT HID;
                // they are handled at the accessibility layer on devices that have the receiver app.
                else -> true
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

        _connectionState.value = ConnectionState.Connecting

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
    }

    /**
     * Not used in BT HID mode — input events bypass the Packet serialization layer
     * and go directly through [sendInputEvent]. Always returns false.
     */
    override suspend fun send(packet: Packet): Boolean = false

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

        // registerApp() returns false if the call itself failed synchronously.
        // The async result arrives in hidCallback.onAppStatusChanged().
        val callOk = hid.registerApp(
            sdp,
            /* qosOut */ null,  // best-effort QoS
            /* qosIn  */ null,
            Executors.newSingleThreadExecutor(),
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
        if (!confirmed) {
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
