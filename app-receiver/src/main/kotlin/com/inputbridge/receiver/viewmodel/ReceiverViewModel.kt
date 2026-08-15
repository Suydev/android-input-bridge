package com.inputbridge.receiver.viewmodel

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inputbridge.core.config.AppConfig
import com.inputbridge.core.config.DisplayConfig
import com.inputbridge.core.config.MouseConfig
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.diagnostics.DiagnosticsData
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.service.CursorOverlayService
import com.inputbridge.receiver.service.ReceiverService
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ReceiverViewModel(
    private val context: Context,
    private val prefs: ReceiverPreferences,
) : ViewModel() {

    val diagnostics: StateFlow<DiagnosticsData> = DiagnosticsManager.state

    val isReceiverActive: StateFlow<Boolean> = diagnostics
        .map { it.receiverServiceRunning }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val connectionLabel: StateFlow<String> = diagnostics
        .map { d ->
            when {
                d.transportConnected     -> "Bridge connected"
                d.receiverServiceRunning -> "Waiting for bridge…"
                else                     -> "Receiver stopped"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Receiver stopped")

    val isAccessibilityEnabled: StateFlow<Boolean> = diagnostics
        .map { it.accessibilityEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The session PIN displayed to the bridge user for pairing entry. */
    val sessionPin: StateFlow<String> = diagnostics
        .map { it.sessionPin.ifEmpty { prefs.sessionPin } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.sessionPin)

    /** Whether this receiver is currently paired with a bridge. */
    val isPaired: StateFlow<Boolean> = diagnostics
        .map { it.isPaired }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isPaired)

    // ── Network / permission status ────────────────────────────────────────────

    private val _isNetworkAvailable = MutableStateFlow(checkNetworkAvailable())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    // ── Trackpad transport (reverse direction: receiver → bridge) ──────────────

    /**
     * UDP transport for sending trackpad input events back to the bridge.
     * Lazily initialized when the trackpad screen is opened.
     * Connects to the paired bridge IP on the same port the receiver listens on.
     */
    @Volatile
    var trackpadTransport: UdpTransport? = null
        private set

    init {
        // BUG-080 FIX: this must follow _isNetworkAvailable's construction. Kotlin runs
        // property initializers and init blocks in source order, so calling refreshStatus()
        // earlier dereferenced the uninitialized backing StateFlow during app startup.
        if (prefs.sessionPin.isEmpty()) {
            prefs.generateNewPin()
        }
        DiagnosticsManager.update {
            copy(
                sessionPin   = prefs.sessionPin,
                isPaired     = prefs.isPaired,
                pairedPeerIp = prefs.pairedBridgeIp,
            )
        }
        refreshStatus()
    }

    /**
     * Refresh permission status and network availability.
     * Call this from the WelcomeScreen DisposableEffect on Lifecycle.Event.ON_RESUME so
     * the status row updates when the user returns from system settings.
     */
    fun refreshStatus() {
        _isNetworkAvailable.value = checkNetworkAvailable()
        // BUG-030 FIX: batteryOptimizationIgnored was never updated at runtime.
        // Now pushed into DiagnosticsManager so the Diagnostics screen and status row
        // both reflect the current state.
        val pm = context.getSystemService(PowerManager::class.java)
        val battOpt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        DiagnosticsManager.update { copy(batteryOptimizationIgnored = battOpt) }
    }

    private fun checkNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ── Full app config ───────────────────────────────────────────────────────

    private val _config = MutableStateFlow(
        AppConfig(
            transport = TransportConfig(port = prefs.port),
            // Mouse scaling is owned by the bridge capture side. Keeping the
            // receiver transparent avoids compounding the multiplier.
            mouse     = MouseConfig(),
            display   = DisplayConfig(
                showCursorOverlay = prefs.showCursorOverlay,
                cursorSizeDp      = prefs.cursorSizeDp,
                autoStartOnBoot   = prefs.autoStartOnBoot,
            ),
        )
    )
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    // ── Transport settings ────────────────────────────────────────────────────

    fun setListenPort(port: Int) {
        _config.update { it.copy(transport = it.transport.copy(port = port)) }
        prefs.port = port
    }

    // ── Display / system settings ─────────────────────────────────────────────

    /**
     * Enable/disable the floating cursor dot overlay.
     * Requires canDrawOverlays() permission. The Settings screen should check
     * this before calling — if permission is absent, direct the user to
     * Settings.ACTION_MANAGE_OVERLAY_PERMISSION first.
     */
    fun setCursorOverlayEnabled(enabled: Boolean) {
        _config.update { it.copy(display = it.display.copy(showCursorOverlay = enabled)) }
        prefs.showCursorOverlay = enabled
    }

    fun setCursorSizeDp(sizeDp: Int) {
        // BUG-098 fix: update the active overlay immediately, not just after a service restart.
        val constrained = sizeDp.coerceIn(
            ReceiverPreferences.MIN_CURSOR_SIZE_DP,
            ReceiverPreferences.MAX_CURSOR_SIZE_DP,
        )
        _config.update { it.copy(display = it.display.copy(cursorSizeDp = constrained)) }
        prefs.cursorSizeDp = constrained
        if (prefs.showCursorOverlay) {
            runCatching { context.stopService(Intent(context, CursorOverlayService::class.java)) }
            runCatching { context.startService(Intent(context, CursorOverlayService::class.java)) }
        }
    }

    /**
     * Enable or disable auto-start on device boot.
     * BootReceiver reads this pref before starting ReceiverService.
     */
    fun setAutoStartOnBoot(enabled: Boolean) {
        _config.update { it.copy(display = it.display.copy(autoStartOnBoot = enabled)) }
        prefs.autoStartOnBoot = enabled
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    /**
     * Generate a fresh session PIN and clear any existing pairing.
     * The bridge operator will need to enter the new PIN to re-pair.
     */
    fun generateNewPin() {
        val pin = prefs.generateNewPin()
        DiagnosticsManager.update { copy(sessionPin = pin, isPaired = false, pairedPeerIp = "") }
    }

    // ── Trackpad transport management ─────────────────────────────────────────

    /**
     * Connect a UDP transport to the paired bridge for sending trackpad events.
     * The bridge's IP is stored in prefs.pairedBridgeIp after pairing.
     * Uses the same port the receiver listens on (the bridge sends to that port).
     */
    fun connectTrackpadTransport() {
        val bridgeIp = prefs.pairedBridgeIp
        if (bridgeIp.isEmpty()) {
            BridgeLogger.w(TAG, "No paired bridge IP — cannot start trackpad")
            DiagnosticsManager.update { copy(lastError = "Pair with bridge first") }
            return
        }
        if (trackpadTransport?.isConnected == true) return

        viewModelScope.launch {
            val config = TransportConfig(targetIp = bridgeIp, port = prefs.port)
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            if (ok) {
                trackpadTransport = transport
                BridgeLogger.i(TAG, "Trackpad transport connected → $bridgeIp:${prefs.port}")
            } else {
                BridgeLogger.e(TAG, "Trackpad transport failed to connect to $bridgeIp")
                DiagnosticsManager.update { copy(lastError = "Trackpad: cannot reach bridge") }
            }
        }
    }

    fun disconnectTrackpadTransport() {
        trackpadTransport?.let { t ->
            viewModelScope.launch {
                runCatching { t.disconnect() }
            }
        }
        trackpadTransport = null
    }

    // ── Service control ───────────────────────────────────────────────────────

    fun startReceiver() {
        viewModelScope.launch {
            runCatching {
                context.startForegroundService(Intent(context, ReceiverService::class.java))
            }.onFailure { e ->
                com.inputbridge.core.logging.BridgeLogger.e(TAG, "Failed to start receiver service: ${e.message}")
                DiagnosticsManager.update {
                    copy(lastError = "Could not start service: ${e.message}")
                }
            }
        }
    }

    fun stopReceiver() {
        viewModelScope.launch {
            runCatching {
                val intent = Intent(context, ReceiverService::class.java)
                intent.action = ReceiverService.ACTION_STOP
                context.startService(intent)
            }.onFailure { e ->
                com.inputbridge.core.logging.BridgeLogger.e(TAG, "Failed to stop receiver service: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectTrackpadTransport()
    }

    private companion object {
        private const val TAG = "ReceiverViewModel"
    }
}
