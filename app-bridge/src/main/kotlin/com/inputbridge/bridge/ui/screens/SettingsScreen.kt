package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.core.config.TransportMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val config     by viewModel.config.collectAsStateWithLifecycle()
    val isPaired   by viewModel.isPaired.collectAsStateWithLifecycle()
    val btAddress  by viewModel.btTargetAddress.collectAsStateWithLifecycle()

    var targetIpInput  by remember { mutableStateOf(config.transport.targetIp) }
    var pinInput       by remember { mutableStateOf(config.security.pairingToken) }
    var btAddressInput by remember { mutableStateOf(btAddress) }

    val isBluetoothHid = config.transport.mode == TransportMode.BLUETOOTH_HID

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

            // ── Transport mode ────────────────────────────────────────────────

            SectionHeader("Transport Mode")

            Text(
                text = "UDP: low-latency Wi-Fi — requires receiver app on the tablet.\n" +
                        "BT HID: Bluetooth keyboard+mouse — works with any device, provides real cursor.",
                color = BridgeDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isBluetoothHid,
                    onClick = { viewModel.setTransportMode(TransportMode.UDP) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor   = BridgePrimary.copy(alpha = 0.15f),
                        activeContentColor     = BridgePrimary,
                        activeBorderColor      = BridgePrimary,
                        inactiveContainerColor = BridgeBackground,
                        inactiveContentColor   = BridgeDim,
                    ),
                ) { Text("UDP", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

                SegmentedButton(
                    selected = isBluetoothHid,
                    onClick = { viewModel.setTransportMode(TransportMode.BLUETOOTH_HID) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor   = BridgePrimary.copy(alpha = 0.15f),
                        activeContentColor     = BridgePrimary,
                        activeBorderColor      = BridgePrimary,
                        inactiveContainerColor = BridgeBackground,
                        inactiveContentColor   = BridgeDim,
                    ),
                ) { Text("BT HID", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }

            Text(
                "⚠  Restart the bridge service after changing transport mode.",
                color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            // ── UDP settings ──────────────────────────────────────────────────

            if (!isBluetoothHid) {
                SectionHeader("UDP Transport")

                OutlinedTextField(
                    value = targetIpInput,
                    onValueChange = { targetIpInput = it; viewModel.setTargetIp(it) },
                    label = { Text("Receiver IP Address", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("192.168.x.x", fontFamily = FontFamily.Monospace) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedTextFieldColors(),
                )

                Text(
                    "Port: ${config.transport.port}",
                    color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )

                HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))
            }

            // ── BT HID settings ───────────────────────────────────────────────

            if (isBluetoothHid) {
                SectionHeader("Bluetooth HID")

                Text(
                    "First pair the host device with this phone in Android Bluetooth Settings. " +
                            "Then optionally enter its MAC address below to auto-connect.",
                    color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )

                OutlinedTextField(
                    value = btAddressInput,
                    onValueChange = { v ->
                        val filtered = v.filter { it.isLetterOrDigit() || it == ':' }
                            .uppercase().take(17)
                        btAddressInput = filtered
                        viewModel.setBtTargetAddress(filtered)
                    },
                    label = { Text("Host BT MAC (optional)", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("A1:B2:C3:D4:E5:F6", fontFamily = FontFamily.Monospace) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedTextFieldColors(),
                )

                Text(
                    "Leave empty to advertise and wait for the host to connect.",
                    color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )

                HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))
            }

            // ── Pairing ───────────────────────────────────────────────────────

            if (!isBluetoothHid) {
                SectionHeader("Pairing")

                Text(
                    "Enter the 6-digit PIN shown on the receiver app.",
                    color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.take(6)
                        pinInput = digits
                        viewModel.setPairingPin(digits)
                    },
                    label = { Text("Pairing PIN", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("123456", fontFamily = FontFamily.Monospace) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedTextFieldColors(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isPaired) "Status: ✓ Paired" else "Status: Not paired",
                        color = if (isPaired) BridgePrimary else BridgeDim,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    if (isPaired) {
                        TextButton(
                            onClick = { viewModel.clearPairing() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("Clear", color = BridgeError, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))
            }

            // ── Mouse ─────────────────────────────────────────────────────────

            SectionHeader("Mouse")

            Text(
                "Sensitivity: %.1f×".format(config.mouse.sensitivity),
                color = BridgeOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            Slider(
                value = config.mouse.sensitivity,
                onValueChange = { viewModel.setBridgeSensitivity(it) },
                valueRange = 0.1f..5f,
                steps = 48,
                colors = SliderDefaults.colors(
                    thumbColor = BridgePrimary,
                    activeTrackColor = BridgePrimary,
                    inactiveTrackColor = BridgeDim.copy(alpha = 0.3f),
                ),
            )
            Text(
                "Applied before packets are sent. Adjust if mouse feels too fast or slow.",
                color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            // ── Display ───────────────────────────────────────────────────────

            SectionHeader("Display")

            SettingsSwitch(
                label = "Keep Screen On",
                description = "Prevent the screen from sleeping while the bridge is active.",
                checked = config.display.keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) },
            )

            SettingsSwitch(
                label = "Show Latency",
                description = "Display the round-trip latency on the active bridge screen.",
                checked = config.display.showLatencyOverlay,
                onCheckedChange = { viewModel.setShowLatencyOverlay(it) },
            )

            SettingsSwitch(
                label = "Black Screen Mode",
                description = "Dims the screen to minimum brightness and hides the UI. " +
                        "Ideal for keeping the phone face-down as a silent relay.",
                checked = config.display.blackScreenMode,
                onCheckedChange = { viewModel.setBlackScreenMode(it) },
                accent = BridgeError,
            )

            if (!config.display.blackScreenMode) {
                Text(
                    "Screen Brightness: ${
                        if (config.display.screenBrightness < 0f) "System default"
                        else "${(config.display.screenBrightness * 100).toInt()}%"
                    }",
                    color = BridgeOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                Slider(
                    value = if (config.display.screenBrightness < 0f) -1f
                            else config.display.screenBrightness,
                    onValueChange = { v ->
                        // Snap values near -1 to system default
                        viewModel.setScreenBrightness(if (v < 0f) -1f else v)
                    },
                    valueRange = -1f..1f,
                    steps = 20,
                    colors = SliderDefaults.colors(
                        thumbColor = BridgePrimary,
                        activeTrackColor = BridgePrimary,
                        inactiveTrackColor = BridgeDim.copy(alpha = 0.3f),
                    ),
                )
                Text(
                    "Drag left for system default. Slide right to force a specific brightness.",
                    color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            // ── System ────────────────────────────────────────────────────────

            SectionHeader("System")

            SettingsSwitch(
                label = "Auto-start on Boot",
                description = "Start the bridge service automatically after the device reboots.",
                checked = config.display.autoStartOnBoot,
                onCheckedChange = { viewModel.setAutoStartOnBoot(it) },
            )

            Text(
                "Emergency stop: hold Volume Down for 3 seconds to stop the bridge immediately.",
                color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Reusable composables ───────────────────────────────────────────────────────

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
private fun SettingsSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: androidx.compose.ui.graphics.Color = BridgePrimary,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = BridgeOnSurface, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor       = accent,
                    checkedTrackColor       = accent.copy(alpha = 0.3f),
                    checkedBorderColor      = accent,
                    uncheckedThumbColor     = BridgeDim,
                    uncheckedTrackColor     = BridgeBackground,
                    uncheckedBorderColor    = BridgeDim,
                ),
            )
        }
        Text(description, color = BridgeDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp)
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = BridgePrimary,
    unfocusedBorderColor = BridgeDim,
    focusedLabelColor    = BridgePrimary,
    unfocusedLabelColor  = BridgeDim,
    focusedTextColor     = BridgeOnSurface,
    unfocusedTextColor   = BridgeOnSurface,
    cursorColor          = BridgePrimary,
)
