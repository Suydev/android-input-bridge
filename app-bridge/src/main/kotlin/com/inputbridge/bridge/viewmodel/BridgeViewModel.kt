package com.inputbridge.bridge.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.core.config.AppConfig
import com.inputbridge.core.config.DisplayConfig
import com.inputbridge.core.config.MouseConfig
import com.inputbridge.core.config.SecurityConfig
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.config.TransportMode
import com.inputbridge.diagnostics.DiagnosticsData
import com.inputbridge.diagnostics.DiagnosticsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the bridge app.
 * Observes DiagnosticsManager and exposes UI state to Compose screens.
 * Controls BridgeService start/stop.
 * Persists transport config, display settings, and pairing settings to [BridgePreferences].
 */
class BridgeViewModel(
    private val context: Context,
    private val prefs: BridgePreferences,
) : ViewModel() {

    /** Full diagnostics snapshot — source of truth for all status UI. */
    val diagnostics: StateFlow<DiagnosticsData> = DiagnosticsManager.state

    /** Derived: is the bridge fully active? */
    val isBridgeActive: StateFlow<Boolean> = diagnostics
        .map { it.bridgeServiceRunning && it.transportConnected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Derived: connection status label for the UI. */
    val connectionLabel: StateFlow<String> = diagnostics
        .map { d ->
            when {
                d.transportConnected   -> "Connected to ${d.targetIp}"
                d.isReconnecting       -> "Reconnecting… (attempt ${d.reconnectAttempts})"
                d.bridgeServiceRunning -> "Service running, not connected"
                else                   -> "Bridge stopped"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Bridge stopped")

    // ── Full app config (persisted to SharedPreferences) ──────────────────────

    private val _config = MutableStateFlow(
        AppConfig(
            transport = TransportConfig(
                mode     = prefs.transportMode,
                targetIp = prefs.targetIp,
                port     = prefs.port,
            ),
            security = SecurityConfig(
                pairingToken = prefs.pairingPin,
            ),
            display = DisplayConfig(
                blackScreenMode    = prefs.blackScreenMode,
                keepScreenOn       = prefs.keepScreenOn,
                showLatencyOverlay = prefs.showLatencyOverlay,
                autoStartOnBoot    = prefs.autoStartOnBoot,
                screenBrightness   = prefs.screenBrightness,
            ),
            mouse = MouseConfig(
                sensitivity = prefs.bridgeSensitivity,
            ),
        )
    )
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    /** Whether pairing has been completed with the current target. */
    val isPaired: StateFlow<Boolean> = diagnostics
        .map { it.isPaired }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isPaired)

    // ── Bluetooth HID settings ────────────────────────────────────────────────

    private val _btTargetAddress = MutableStateFlow(prefs.btTargetDeviceAddress)
    val btTargetAddress: StateFlow<String> = _btTargetAddress.asStateFlow()

    fun setBtTargetAddress(address: String) {
        _btTargetAddress.value = address
        prefs.btTargetDeviceAddress = address
    }

    // ── Transport settings ────────────────────────────────────────────────────

    fun setTransportMode(mode: TransportMode) {
        _config.update { it.copy(transport = it.transport.copy(mode = mode)) }
        prefs.transportMode = mode
    }

    fun setTargetIp(ip: String) {
        _config.update { it.copy(transport = it.transport.copy(targetIp = ip)) }
        prefs.targetIp = ip
        if (prefs.isPaired) prefs.isPaired = false
    }

    fun setPort(port: Int) {
        _config.update { it.copy(transport = it.transport.copy(port = port)) }
        prefs.port = port
    }

    // ── Pairing settings ──────────────────────────────────────────────────────

    fun setPairingPin(pin: String) {
        val trimmed = pin.trim()
        _config.update { it.copy(security = it.security.copy(pairingToken = trimmed)) }
        prefs.setPinAndClearPairing(trimmed)
    }

    fun clearPairing() {
        prefs.isPaired = false
        DiagnosticsManager.update { copy(isPaired = false) }
    }

    // ── Mouse settings ────────────────────────────────────────────────────────

    /**
     * Set bridge-side mouse sensitivity (0.1–5.0).
     * Applied by UsbInputCapture before forwarding events.
     */
    fun setBridgeSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.1f, 5.0f)
        _config.update { it.copy(mouse = it.mouse.copy(sensitivity = clamped)) }
        prefs.bridgeSensitivity = clamped
    }

    // ── Display settings ──────────────────────────────────────────────────────

    /**
     * Toggle black-screen mode.
     * When true: BridgeScreen shows pitch-black UI and window brightness is set to minimum.
     * Useful for keeping the phone face-down while bridging.
     */
    fun setBlackScreenMode(enabled: Boolean) {
        _config.update { it.copy(display = it.display.copy(blackScreenMode = enabled)) }
        prefs.blackScreenMode = enabled
        DiagnosticsManager.update { copy(blackScreenMode = enabled) }
    }

    /** Control whether FLAG_KEEP_SCREEN_ON is applied to the activity window. */
    fun setKeepScreenOn(enabled: Boolean) {
        _config.update { it.copy(display = it.display.copy(keepScreenOn = enabled)) }
        prefs.keepScreenOn = enabled
    }

    /** Show/hide the latency figure on the active bridge screen. */
    fun setShowLatencyOverlay(enabled: Boolean) {
        _config.update { it.copy(display = it.display.copy(showLatencyOverlay = enabled)) }
        prefs.showLatencyOverlay = enabled
    }

    /**
     * Set screen brightness override (-1 = system default, 0.0–1.0 = explicit).
     * Only applied when black-screen mode is off.
     */
    fun setScreenBrightness(brightness: Float) {
        val clamped = if (brightness < 0f) -1f else brightness.coerceIn(0f, 1f)
        _config.update { it.copy(display = it.display.copy(screenBrightness = clamped)) }
        prefs.screenBrightness = clamped
    }

    // ── System settings ───────────────────────────────────────────────────────

    /**
     * Enable or disable auto-start on device boot.
     * BootReceiver reads this pref before starting BridgeService.
     */
    fun setAutoStartOnBoot(enabled: Boolean) {
        _config.update { it.copy(display = it.display.copy(autoStartOnBoot = enabled)) }
        prefs.autoStartOnBoot = enabled
    }

    // ── Service control ───────────────────────────────────────────────────────

    fun startBridge() {
        viewModelScope.launch {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }

    fun stopBridge() {
        viewModelScope.launch {
            val intent = Intent(context, BridgeService::class.java)
            intent.action = BridgeService.ACTION_STOP
            context.startService(intent)
        }
    }
}
