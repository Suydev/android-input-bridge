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
 * Persists transport config changes to [BridgePreferences] so BridgeService
 * can read them at service start time.
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
                d.bridgeServiceRunning -> "Service running, not connected"
                else                   -> "Bridge stopped"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Bridge stopped")

    // ── Current config (editable in settings, persisted to SharedPreferences) ─

    private val _config = MutableStateFlow(
        AppConfig(
            transport = TransportConfig(
                targetIp = prefs.targetIp,
                port = prefs.port,
            )
        )
    )
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    // ── Transport mode selection ───────────────────────────────────────────────

    fun setTransportMode(mode: TransportMode) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(mode = mode)
        )
    }

    fun setTargetIp(ip: String) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(targetIp = ip)
        )
        prefs.targetIp = ip  // persist for BridgeService to read at start
    }

    fun setPort(port: Int) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(port = port)
        )
        prefs.port = port  // persist for BridgeService to read at start
    }

    // ── Service control ───────────────────────────────────────────────────────

    fun startBridge() {
        viewModelScope.launch {
            val intent = Intent(context, BridgeService::class.java)
            context.startForegroundService(intent)
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
