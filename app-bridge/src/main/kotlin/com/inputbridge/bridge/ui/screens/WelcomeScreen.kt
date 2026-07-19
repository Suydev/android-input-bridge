package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.core.config.TransportMode

/**
 * Welcome screen: first launch experience.
 * Shows mode selection and a permission status summary before starting.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BridgeBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header
        Column {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "InputBridge",
                color = BridgePrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Redmi 9 → OnePlus Pad Go",
                color = BridgeDim,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Mode selector
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Transport Mode",
                color = BridgeOnSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TransportMode.entries.forEach { mode ->
                val selected = config.transport.mode == mode
                OutlinedButton(
                    onClick = { viewModel.setTransportMode(mode) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) BridgePrimary else BridgeDim,
                    ),
                ) {
                    Text(
                        text = mode.name.replace("_", " "),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // Status summary
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusRow("USB Device", diagnostics.usbDeviceConnected)
            StatusRow("Network", true) // always available
            StatusRow("Battery Optimization", diagnostics.batteryOptimizationIgnored)
        }

        // Actions
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BridgePrimary),
            ) {
                Text("Start Bridge", color = BridgeBackground, fontFamily = FontFamily.Monospace)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onPermissions) {
                    Text("Permissions", color = BridgeDim, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = onSettings) {
                    Text("Settings", color = BridgeDim, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (ok) "✓" else "✗",
            color = if (ok) BridgePrimary else BridgeError,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = label,
            color = if (ok) BridgeOnSurface else BridgeDim,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
