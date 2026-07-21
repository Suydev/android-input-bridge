package com.inputbridge.receiver.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inputbridge.core.config.AppConfig
import com.inputbridge.core.config.MouseConfig
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.diagnostics.DiagnosticsData
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.service.ReceiverService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ReceiverViewModel(
    private val context: Context,
    private val prefs: ReceiverPreferences,
) : ViewModel() {

    init {
        // Ensure a PIN exists at ViewModel creation time so it's available
        // before ReceiverService starts (shown on ConnectionScreen immediately).
        if (prefs.sessionPin.isEmpty()) {
            prefs.generateNewPin()
        }
        // Publish current PIN into DiagnosticsManager so the UI sees it even
        // before the service initialises the field itself.
        DiagnosticsManager.update {
            copy(
                sessionPin = prefs.sessionPin,
                isPaired = prefs.isPaired,
                pairedPeerIp = prefs.pairedBridgeIp,
            )
        }
    }

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

    private val _config = MutableStateFlow(
        AppConfig(
            transport = TransportConfig(port = prefs.port),
            mouse = MouseConfig(sensitivity = prefs.mouseSensitivity),
        )
    )
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    fun setListenPort(port: Int) {
        _config.value = _config.value.copy(
            transport = _config.value.transport.copy(port = port)
        )
        prefs.port = port
    }

    /**
     * Update the mouse sensitivity multiplier.
     * Range 0.1–5.0. Persisted and applied immediately if service is running.
     */
    fun setMouseSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.1f, 5.0f)
        _config.value = _config.value.copy(
            mouse = _config.value.mouse.copy(sensitivity = clamped)
        )
        prefs.mouseSensitivity = clamped
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            com.inputbridge.accessibility.AccessibilityCommandBus.setSensitivity(clamped)
        }
    }

    /**
     * Generate a fresh session PIN and clear any existing pairing.
     * The bridge operator will need to enter the new PIN to re-pair.
     */
    fun generateNewPin() {
        val pin = prefs.generateNewPin()
        DiagnosticsManager.update { copy(sessionPin = pin, isPaired = false, pairedPeerIp = "") }
    }

    fun startReceiver() {
        viewModelScope.launch {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }
    }

    fun stopReceiver() {
        viewModelScope.launch {
            val intent = Intent(context, ReceiverService::class.java)
            intent.action = ReceiverService.ACTION_STOP
            context.startService(intent)
        }
    }
}
