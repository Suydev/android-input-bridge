package com.inputbridge.receiver.ui.screens

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
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverDiagnosticsScreen(onBack: () -> Unit, viewModel: ReceiverViewModel) {
    val d by viewModel.diagnostics.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ReceiverOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReceiverSurface, titleContentColor = ReceiverOnSurface)
            )
        },
        containerColor = ReceiverBackground,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            RRow("Service", if (d.receiverServiceRunning) "RUNNING" else "STOPPED", d.receiverServiceRunning)
            RRow("Accessibility", if (d.accessibilityEnabled) "ENABLED" else "DISABLED", d.accessibilityEnabled)
            RRow("Secure Window", if (d.isSecureWindow) "BLOCKED" else "OK", !d.isSecureWindow)
            RRow("Connection", if (d.transportConnected) "CONNECTED" else "DISCONNECTED", d.transportConnected)
            RRow("Paired", if (d.isPaired) "YES (${d.pairedPeerIp})" else "NO", d.isPaired)
            RRow("Session PIN", d.sessionPin.ifEmpty { "—" }, d.sessionPin.isNotEmpty())
            RRow("Latency (last)", "${d.latencyMs}ms", d.latencyMs in 1..100)
            RRow("Latency (avg)", "${d.latencyAvgMs}ms", d.latencyAvgMs in 1..100)
            RRow("Recv→Inject", if (d.receiveToInjectUs > 0) "${d.receiveToInjectUs}µs" else "—", true)
            RRow("Packets Recv", d.packetsReceived.toString(), true)
            RRow("Packets Sent", d.packetsSent.toString(), true)
            RRow("Dropped (seq)", d.packetsDroppedSequence.toString(), d.packetsDroppedSequence == 0L)
            if (d.lastInjectionError != null) {
                Spacer(Modifier.height(8.dp))
                Text("INJECT ERROR", color = ReceiverError, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(d.lastInjectionError!!, color = ReceiverError, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
            }
            if (d.lastError != null) {
                Spacer(Modifier.height(12.dp))
                Text("LAST ERROR", color = ReceiverError, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(d.lastError!!, color = ReceiverError, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RRow(key: String, value: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = ReceiverDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = if (ok) ReceiverOnSurface else ReceiverError, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
