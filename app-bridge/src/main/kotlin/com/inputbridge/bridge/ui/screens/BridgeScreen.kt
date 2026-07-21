package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.BugReport
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
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel

/**
 * Active bridge screen — shown while the bridge is running.
 * Intentionally minimal: mostly black with status indicators.
 *
 * Design goal: absolute minimum light emission. The Redmi should function
 * as a dark console, not a bright screen. Users will typically keep this
 * screen face-down or rely on the foreground service notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isBridgeActive by viewModel.isBridgeActive.collectAsStateWithLifecycle()
    val connectionLabel by viewModel.connectionLabel.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Top bar — tiny icons only, no colour bleed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDiagnostics) {
                Icon(Icons.Default.BugReport, contentDescription = "Diagnostics",
                    tint = BridgeDim, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings",
                    tint = BridgeDim, modifier = Modifier.size(20.dp))
            }
        }

        // Centre status cluster
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mode badge
            Text(
                text = diagnostics.transportMode,
                color = BridgeDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )

            // Connection status dot + label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val dotColor = when {
                    diagnostics.transportConnected -> BridgePrimary
                    diagnostics.bridgeServiceRunning -> BridgeWarning
                    else -> BridgeError
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, shape = androidx.compose.foundation.shape.CircleShape)
                )
                Text(
                    text = connectionLabel,
                    color = BridgeOnSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Latency display
            if (diagnostics.transportConnected && diagnostics.latencyMs > 0) {
                val avgSuffix = if (diagnostics.latencyAvgMs > 0 && diagnostics.latencyAvgMs != diagnostics.latencyMs)
                    " · avg ${diagnostics.latencyAvgMs}ms" else ""
                Text(
                    text = "${diagnostics.latencyMs}ms$avgSuffix",
                    color = BridgeDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // USB status
            Text(
                text = if (diagnostics.usbDeviceConnected)
                    "⌨ ${diagnostics.usbDeviceName}" else "No USB device",
                color = if (diagnostics.usbDeviceConnected) BridgePrimary else BridgeDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Emergency stop — bottom centre, small but reachable
        TextButton(
            onClick = { viewModel.stopBridge() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "STOP",
                color = BridgeError,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
        }

        // Start button (only when not active)
        if (!isBridgeActive) {
            Button(
                onClick = { viewModel.startBridge() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .fillMaxWidth(0.5f),
                colors = ButtonDefaults.buttonColors(containerColor = BridgePrimary),
            ) {
                Text("START", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
