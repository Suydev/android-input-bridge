package com.inputbridge.receiver.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inputbridge.receiver.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilitySetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accessibility Setup", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ReceiverOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReceiverSurface, titleContentColor = ReceiverOnSurface)
            )
        },
        containerColor = ReceiverBackground,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Why is this needed?", color = ReceiverPrimary, fontFamily = FontFamily.Monospace)
            Text(
                "The Accessibility Service lets InputBridge inject taps, swipes, keyboard input, " +
                    "and navigation commands on your behalf. It does not monitor what you type or do.",
                color = ReceiverOnSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )

            Card(colors = CardDefaults.cardColors(containerColor = ReceiverSurface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IMPORTANT LIMITATION", color = ReceiverWarning, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    Text(
                        "Accessibility mode does NOT create a real hardware mouse cursor. " +
                            "It simulates taps and gestures at the cursor position. " +
                            "For a real cursor, use Bluetooth HID mode on the bridge app.",
                        color = ReceiverDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Text("Setup steps:", color = ReceiverOnSurface, fontFamily = FontFamily.Monospace)
            listOf(
                "1. Tap 'Open Accessibility Settings' below.",
                "2. Find 'InputBridge Input Controller' in the list.",
                "3. Tap it and enable the service.",
                "4. Accept the permission dialog.",
                "5. Return here — the status will update automatically.",
            ).forEach {
                Text(it, color = ReceiverDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }

            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ReceiverPrimary),
            ) {
                Text("Open Accessibility Settings", color = ReceiverBackground, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
