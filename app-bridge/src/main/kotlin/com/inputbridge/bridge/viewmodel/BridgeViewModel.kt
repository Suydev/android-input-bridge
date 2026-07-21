package com.inputbridge.bridge.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.core.config.AppConfig
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
 * Persists transport config and pairing settings to [BridgePreferences].
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

    // ── Current config (editable in settings, persisted to SharedPreferences) ─

    private val _config = MutableStateFlow(
        AppConfig(
            transport = TransportConfig(
                mode     = prefs.transportMode,
                targetIp = prefs.targetIp,
                port     = prefs.port,
            ),
            security = com.inputbridge.core.config.SecurityConfig(
                pairingToken = prefs.pairingPin,
            ),
        )
    )
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    /** Whether pairing has been completed with the current target. */
    val isPaired: StateFlow<Boolean> = diagnostics
        .map { it.isPaired }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isPaired)

    // ── Bluetooth HID settings ────────────────────────────────────────────────

    /**
     * Bluetooth MAC address of the HID host to connect to.
     * Empty = wait for any host to connect after BT HID registration.
     */
    private val _btTargetAddress = MutableStateFlow(prefs.btTargetDeviceAddress)
    val btTargetAddress: StateFlow<String> = _btTargetAddress.asStateFlow()

    fun setBtTargetAddress(address: String) {
        _btTargetAddress.value = address
        prefs.btTargetDeviceAddress = address
    }

    // ── Transport settings ────────────────────────────────────────────────────

    /**
     * Switch the active transport mode and persist the choice.
     * Takes effect on the next BridgeService start.
     */
    fun setTransportMode(mode: TransportMode) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(mode = mode)
        )
        prefs.transportMode = mode
    }

    fun setTargetIp(ip: String) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(targetIp = ip)
        )
        prefs.targetIp = ip
        // Changing the target IP invalidates existing pairing
        if (prefs.isPaired) {
            prefs.isPaired = false
        }
    }

    fun setPort(port: Int) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(port = port)
        )
        prefs.port = port
    }

    // ── Pairing settings ──────────────────────────────────────────────────────

    /**
     * Set the pairing PIN entered by the user (copied from the receiver's display).
     * Automatically clears any existing pairing so the next service start
     * performs a fresh PAIR_REQUEST handshake.
     */
    fun setPairingPin(pin: String) {
        val trimmed = pin.trim()
        _config.value = _config.value.copy(
            security = _config.value.security.copy(pairingToken = trimmed)
        )
        prefs.setPinAndClearPairing(trimmed)
    }

    /**
     * Clear the stored pairing so the next service start re-pairs.
     * Does not change the PIN itself.
     */
    fun clearPairing() {
        prefs.isPaired = false
        DiagnosticsManager.update { copy(isPaired = false) }
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
