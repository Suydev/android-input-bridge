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
    val sessionPin by viewModel.sessionPin.collectAsStateWithLifecycle()
    val isPaired by viewModel.isPaired.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Top toolbar ───────────────────────────────────────────────────────
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

        // ── Centre status cluster ─────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                diagnostics.accessibilityMode.ifEmpty { "ACCESSIBILITY" },
                color = ReceiverDim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )

            // Connection dot + label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val dotColor = when {
                    diagnostics.transportConnected     -> ReceiverPrimary
                    diagnostics.receiverServiceRunning -> ReceiverWarning
                    else                               -> ReceiverError
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dotColor, androidx.compose.foundation.shape.CircleShape)
                )
                Text(
                    connectionLabel, color = ReceiverOnSurface,
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                )
            }

            // Latency
            if (diagnostics.transportConnected && diagnostics.latencyMs > 0) {
                Text(
                    "${diagnostics.latencyMs}ms",
                    color = ReceiverDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }

            // Accessibility status
            Text(
                "Accessibility: ${if (diagnostics.accessibilityEnabled) "Active" else "Not enabled"}",
                color = if (diagnostics.accessibilityEnabled) ReceiverPrimary else ReceiverWarning,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.width(200.dp),
                color = ReceiverDim.copy(alpha = 0.25f),
            )
            Spacer(Modifier.height(8.dp))

            // ── Pairing PIN display ───────────────────────────────────────────
            Text(
                "PAIRING PIN",
                color = ReceiverDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )

            Text(
                text = if (sessionPin.isNotEmpty()) sessionPin else "------",
                color = if (isPaired) ReceiverDim else ReceiverOnSurface,
                fontSize = 36.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
            )

            if (isPaired) {
                Text(
                    "✓ Paired with ${diagnostics.pairedPeerIp}",
                    color = ReceiverPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    "Enter this PIN in the bridge app Settings",
                    color = ReceiverDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            TextButton(
                onClick = { viewModel.generateNewPin() },
            ) {
                Text(
                    "REGENERATE PIN",
                    color = ReceiverDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                )
            }
        }

        // ── Start / Stop buttons ──────────────────────────────────────────────
        if (!isReceiverActive) {
            Button(
                onClick = { viewModel.startReceiver() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .fillMaxWidth(0.5f),
                colors = ButtonDefaults.buttonColors(containerColor = ReceiverPrimary),
            ) {
                Text("START", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
        }

        TextButton(
            onClick = { viewModel.stopReceiver() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        ) {
            Text(
                "STOP", color = ReceiverError, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
            )
        }
    }
}
