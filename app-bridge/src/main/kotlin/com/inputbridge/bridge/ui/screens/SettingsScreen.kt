package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var targetIpInput by remember { mutableStateOf(config.transport.targetIp) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = FontFamily.Monospace) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            SectionHeader("Transport")

            OutlinedTextField(
                value = targetIpInput,
                onValueChange = {
                    targetIpInput = it
                    viewModel.setTargetIp(it)
                },
                label = { Text("Receiver IP Address", fontFamily = FontFamily.Monospace) },
                placeholder = { Text("192.168.x.x", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedTextFieldColors(),
            )

            Text(
                text = "Port: ${config.transport.port}",
                color = BridgeDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            SectionHeader("Mouse")

            Text("Sensitivity: %.1f×".format(config.mouse.sensitivity),
                color = BridgeOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Slider(
                value = config.mouse.sensitivity,
                onValueChange = { /* Phase 7: wire to viewModel.setSensitivity(it) */ },
                valueRange = 0.1f..5f,
                colors = SliderDefaults.colors(thumbColor = BridgePrimary, activeTrackColor = BridgePrimary),
            )

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            SectionHeader("Display")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Keep Screen On", color = BridgeOnSurface, fontFamily = FontFamily.Monospace)
                Switch(
                    checked = config.display.keepScreenOn,
                    onCheckedChange = { /* Phase 7 */ },
                    colors = SwitchDefaults.colors(checkedThumbColor = BridgePrimary),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show Latency", color = BridgeOnSurface, fontFamily = FontFamily.Monospace)
                Switch(
                    checked = config.display.showLatencyOverlay,
                    onCheckedChange = { /* Phase 7 */ },
                    colors = SwitchDefaults.colors(checkedThumbColor = BridgePrimary),
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = BridgePrimary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BridgePrimary,
    unfocusedBorderColor = BridgeDim,
    focusedLabelColor = BridgePrimary,
    unfocusedLabelColor = BridgeDim,
    focusedTextColor = BridgeOnSurface,
    unfocusedTextColor = BridgeOnSurface,
    cursorColor = BridgePrimary,
)
