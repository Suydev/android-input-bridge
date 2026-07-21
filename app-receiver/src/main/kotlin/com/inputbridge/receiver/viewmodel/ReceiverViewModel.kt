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
        prefs.port = port  // persist for ReceiverService to read at start
    }

    /**
     * Update the mouse sensitivity multiplier.
     * Range 0.1–5.0. Persisted to prefs and applied to AccessibilityCommandBus
     * the next time ReceiverService starts.
     */
    fun setMouseSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.1f, 5.0f)
        _config.value = _config.value.copy(
            mouse = _config.value.mouse.copy(sensitivity = clamped)
        )
        prefs.mouseSensitivity = clamped
        // If the service is running, apply immediately without restart
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            com.inputbridge.accessibility.AccessibilityCommandBus.setSensitivity(clamped)
        }
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
