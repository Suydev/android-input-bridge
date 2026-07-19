package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inputbridge.bridge.BuildConfig
import com.inputbridge.bridge.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About", fontFamily = FontFamily.Monospace) },
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
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("InputBridge", color = BridgePrimary, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = BridgeDim, fontFamily = FontFamily.Monospace)
            Text("Build: ${if (BuildConfig.DEBUG) "Debug" else "Release"}",
                color = BridgeDim, fontFamily = FontFamily.Monospace)

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            Text("Bridge App", color = BridgeOnSurface, fontFamily = FontFamily.Monospace)
            Text(
                "Runs on: Redmi 9\nReads: Portronics Key2 Combo via OTG\nSends to: OnePlus Pad Go",
                color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            Text(
                "This app operates entirely offline. No data is sent to external servers. " +
                    "No accounts required. No telemetry.",
                color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}
