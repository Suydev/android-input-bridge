package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.diagnostics.DiagnosticsData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BridgeOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BridgeSurface,
                    titleContentColor = BridgeOnSurface,
                )
            )
        },
        containerColor = BridgeBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            DiagRow("Service", if (diagnostics.bridgeServiceRunning) "RUNNING" else "STOPPED",
                diagnostics.bridgeServiceRunning)
            DiagRow("USB Device", if (diagnostics.usbDeviceConnected) diagnostics.usbDeviceName else "None",
                diagnostics.usbDeviceConnected)
            DiagRow("Input Capture", if (diagnostics.inputCaptureActive) "ACTIVE" else "IDLE",
                diagnostics.inputCaptureActive)
            DiagRow("Transport", diagnostics.transportMode, true)
            DiagRow("Connection", if (diagnostics.transportConnected) "CONNECTED" else "DISCONNECTED",
                diagnostics.transportConnected)
            DiagRow("BT Host",
                if (diagnostics.btConnected) diagnostics.btDeviceName.ifEmpty { "CONNECTED" } else "—",
                diagnostics.btConnected)
            DiagRow("Target IP", diagnostics.targetIp.ifEmpty { "Not set" }, diagnostics.targetIp.isNotEmpty())
            DiagRow("Latency (last)", "${diagnostics.latencyMs}ms", diagnostics.latencyMs in 1..100)
            DiagRow("Latency (avg)", "${diagnostics.latencyAvgMs}ms", diagnostics.latencyAvgMs in 1..100)
            DiagRow("Capture→Send", if (diagnostics.captureToSendUs > 0) "${diagnostics.captureToSendUs}µs" else "—", true)
            DiagRow("Packets Sent", diagnostics.packetsSent.toString(), true)
            DiagRow("Packets Recv", diagnostics.packetsReceived.toString(), true)
            DiagRow("Send Failures", diagnostics.packetsSendFailed.toString(),
                diagnostics.packetsSendFailed == 0L)
            DiagRow("Paired", if (diagnostics.isPaired) "YES" else "NO", diagnostics.isPaired)
            DiagRow("Reconnects", diagnostics.reconnectAttempts.toString(), diagnostics.reconnectAttempts == 0)
            DiagRow("Reconnecting", if (diagnostics.isReconnecting) "YES" else "NO", !diagnostics.isReconnecting)
            DiagRow("USB Permission", if (diagnostics.usbPermissionGranted) "GRANTED" else "DENIED",
                diagnostics.usbPermissionGranted)
            DiagRow("Battery Opt", if (diagnostics.batteryOptimizationIgnored) "IGNORED" else "ACTIVE",
                diagnostics.batteryOptimizationIgnored)

            if (diagnostics.lastError != null) {
                Spacer(Modifier.height(12.dp))
                Text("LAST ERROR", color = BridgeError, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                Text(diagnostics.lastError!!, color = BridgeError, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DiagRow(key: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = if (ok) BridgeOnSurface else BridgeError,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
