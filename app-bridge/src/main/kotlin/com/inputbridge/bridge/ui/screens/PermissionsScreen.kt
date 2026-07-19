package com.inputbridge.bridge.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inputbridge.bridge.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BridgeOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BridgeSurface, titleContentColor = BridgeOnSurface)
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

            PermissionItem(
                title = "USB Host Access",
                description = "Required to read keyboard and mouse input from the Portronics receiver.",
                granted = true, // Always granted via intent-filter; no runtime permission needed
                action = null,
            )
            PermissionItem(
                title = "Battery Optimization Exemption",
                description = "Required to keep the bridge alive when the screen is off. Without this, Android may kill the service.",
                granted = false,
                action = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).also {
                        it.data = Uri.parse("package:${context.packageName}")
                    })
                },
                actionLabel = "Open Settings",
            )
            PermissionItem(
                title = "Nearby Wi-Fi Devices",
                description = "Required for Wi-Fi Direct mode. Not needed for UDP/hotspot mode.",
                granted = false,
                action = null,
                actionLabel = "Grant in App Settings",
            )
            PermissionItem(
                title = "Bluetooth Connect",
                description = "Required only if you enable Bluetooth HID mode for hardware cursor support.",
                granted = false,
                action = null,
                actionLabel = "Grant in App Settings",
            )

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            Text(
                text = "MIUI / ColorOS note: You must also enable 'Autostart' in your phone's security settings. " +
                    "Go to Settings → Apps → InputBridge → Autostart and enable it.",
                color = BridgeWarning,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    action: (() -> Unit)?,
    actionLabel: String = "",
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgeSurface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (granted) BridgePrimary else BridgeWarning,
                    modifier = Modifier.size(18.dp),
                )
                Text(title, color = BridgeOnSurface, fontFamily = FontFamily.Monospace)
            }
            Text(description, color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (action != null) {
                TextButton(onClick = action) {
                    Text(actionLabel, color = BridgePrimary, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
