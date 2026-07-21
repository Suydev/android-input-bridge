package com.inputbridge.receiver.ui.screens

import androidx.compose.foundation.BorderStroke
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

/**
 * Bug fixes applied here:
 *
 * BUG-030 (UI part): STOP button was always visible, even when the service was stopped.
 *   Pressing STOP on a stopped service silently started and immediately stopped it —
 *   no crash but confusing empty-state semantics. STOP now only renders when
 *   isReceiverActive == true.
 *
 * UX polish: START button changed from solid BridgePrimary to OutlinedButton to match
 *   the dark terminal aesthetic seen in the screenshots.
 */
@Composable
fun ConnectionScreen(
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics      by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isReceiverActive by viewModel.isReceiverActive.collectAsStateWithLifecycle()
    val connectionLabel  by viewModel.connectionLabel.collectAsStateWithLifecycle()
    val sessionPin       by viewModel.sessionPin.collectAsStateWithLifecycle()
    val isPaired         by viewModel.isPaired.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Top toolbar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDiagnostics) {
                Icon(
                    Icons.Default.BugReport, "Diagnostics",
                    tint = ReceiverDim, modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings, "Settings",
                    tint = ReceiverDim, modifier = Modifier.size(20.dp),
                )
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
                    "Enter this PIN in the bridge app → Settings → Pairing PIN",
                    color = ReceiverDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            TextButton(onClick = { viewModel.generateNewPin() }) {
                Text(
                    "REGENERATE PIN",
                    color = ReceiverDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                )
            }
        }

        // ── BT HID mode info card ─────────────────────────────────────────────
        // Always shown so users who selected BT HID on the bridge understand
        // this app is not needed in that mode.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
                .padding(horizontal = 24.dp),
        ) {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = ReceiverSurface.copy(alpha = 0.85f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "BLUETOOTH HID MODE",
                        color = ReceiverWarning, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    )
                    Text(
                        "If the bridge app is set to BT HID mode this receiver app is NOT needed. " +
                        "The bridge phone connects directly as a Bluetooth keyboard+mouse — " +
                        "pair it via Settings → Bluetooth on this device.",
                        color = ReceiverDim, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // ── Start / Stop buttons ──────────────────────────────────────────────

        // START: only shown when inactive. Outlined style matches terminal aesthetic.
        if (!isReceiverActive) {
            OutlinedButton(
                onClick = { viewModel.startReceiver() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .fillMaxWidth(0.5f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ReceiverPrimary),
                border = BorderStroke(1.dp, ReceiverPrimary.copy(alpha = 0.8f)),
            ) {
                Text(
                    "START", fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
            }
        }

        // STOP: BUG-030 FIX — only shown when service is actually running.
        // Emergency stop is always available via Volume Down (3s hold).
        if (isReceiverActive) {
            TextButton(
                onClick = { viewModel.stopReceiver() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    "STOP",
                    color = ReceiverError, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                )
            }
        }
    }
}
