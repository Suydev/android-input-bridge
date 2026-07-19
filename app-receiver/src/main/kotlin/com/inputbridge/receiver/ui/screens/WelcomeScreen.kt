package com.inputbridge.receiver.ui.screens

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
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel

@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onAccessibility: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().background(ReceiverBackground).padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Spacer(Modifier.height(48.dp))
            Text("InputBridge", color = ReceiverPrimary, fontSize = 32.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Receiver", color = ReceiverDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusItem("Accessibility Service", isAccessibilityEnabled)
            StatusItem("Network Ready", true)
            StatusItem("Boot Auto-start", diagnostics.batteryOptimizationIgnored)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isAccessibilityEnabled) {
                OutlinedButton(
                    onClick = onAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ReceiverWarning),
                ) {
                    Text("Enable Accessibility Service", fontFamily = FontFamily.Monospace)
                }
            }
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ReceiverPrimary),
            ) {
                Text("Start Receiver", color = ReceiverBackground, fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Settings", color = ReceiverDim, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun StatusItem(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (ok) "✓" else "✗", color = if (ok) ReceiverPrimary else ReceiverError,
            fontFamily = FontFamily.Monospace)
        Text(label, color = if (ok) ReceiverOnSurface else ReceiverDim,
            fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}
