package com.inputbridge.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel

@Composable
fun ConnectionScreen(
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isReceiverActive by viewModel.isReceiverActive.collectAsStateWithLifecycle()
    val connectionLabel by viewModel.connectionLabel.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDiagnostics) {
                Icon(Icons.Default.BugReport, "Diagnostics", tint = ReceiverDim, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = ReceiverDim, modifier = Modifier.size(20.dp))
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(diagnostics.accessibilityMode.ifEmpty { "ACCESSIBILITY" },
                color = ReceiverDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dotColor = when {
                    diagnostics.transportConnected  -> ReceiverPrimary
                    diagnostics.receiverServiceRunning -> ReceiverWarning
                    else -> ReceiverError
                }
                Box(Modifier.size(8.dp).background(dotColor, androidx.compose.foundation.shape.CircleShape))
                Text(connectionLabel, color = ReceiverOnSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }

            if (diagnostics.transportConnected && diagnostics.latencyMs > 0) {
                Text("${diagnostics.latencyMs}ms", color = ReceiverDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            Text("Accessibility: ${if (diagnostics.accessibilityEnabled) "Active" else "Not enabled"}",
                color = if (diagnostics.accessibilityEnabled) ReceiverPrimary else ReceiverWarning,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        if (!isReceiverActive) {
            Button(
                onClick = { viewModel.startReceiver() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).fillMaxWidth(0.5f),
                colors = ButtonDefaults.buttonColors(containerColor = ReceiverPrimary),
            ) { Text("START", color = Color.Black, fontFamily = FontFamily.Monospace) }
        }

        TextButton(
            onClick = { viewModel.stopReceiver() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        ) {
            Text("STOP", color = ReceiverError, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        }
    }
}
