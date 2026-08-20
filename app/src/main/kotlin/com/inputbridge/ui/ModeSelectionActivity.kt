package com.inputbridge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.inputbridge.R
import com.inputbridge.core.config.AppRole
import com.inputbridge.core.config.AppRoleStore
import com.inputbridge.ui.bridge.BridgeModeActivity
import com.inputbridge.ui.receiver.ReceiverModeActivity
import androidx.compose.ui.text.style.TextAlign

class ModeSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModeSelectionScreen(
                onBridgeClick = {
                    // BUG-141 FIX: persist the role so BootReceivers only ever auto-start the
                    // service for this device's chosen mode (single-APK merge fix:
                    // without this, both BridgeService and ReceiverService could run
                    // in the same process and collide on discovery port 54322).
                    AppRoleStore.set(this, AppRole.BRIDGE)
                    startActivity(Intent(this, BridgeModeActivity::class.java))
                },
                onReceiverClick = {
                    AppRoleStore.set(this, AppRole.RECEIVER)
                    startActivity(Intent(this, ReceiverModeActivity::class.java))
                },
            )
        }
    }
}

@Composable
fun ModeSelectionScreen(
    onBridgeClick: () -> Unit,
    onReceiverClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "InputBridge",
                fontSize = 32.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(32.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                ModeCard(
                    title = "Bridge Mode",
                    subtitle = "Phone with USB dongle → Tablet\nCapture USB keyboard/mouse, send via Wi-Fi or Bluetooth",
                    icon = "📱",
                    onClick = onBridgeClick
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
                ModeCard(
                    title = "Receiver Mode",
                    subtitle = "Tablet receives input\nInjects keyboard/mouse via Accessibility + Shizuku",
                    icon = "📟",
                    onClick = onReceiverClick
                )
            }
        }
    }
}

@Composable
fun ModeCard(
    title: String,
    subtitle: String,
    icon: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(160.dp),
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = icon,
                fontSize = 48.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Text(
                text = title,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3
            )
        }
    }
}