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
import com.inputbridge.core.logging.CrashLog
import com.inputbridge.ui.bridge.BridgeModeActivity
import com.inputbridge.ui.receiver.ReceiverModeActivity
import androidx.compose.ui.text.style.TextAlign

class ModeSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BUG-188 FIX: reopening the app must NOT dump the user back on this screen
        // while their chosen role's service is still running — jump straight into it.
        // When the service is dead (e.g. OEM killed it), fall through to the selector
        // so switching roles stays possible.
        val role = AppRoleStore.get(this)
        val svcRunning = com.inputbridge.diagnostics.DiagnosticsManager.state.value.let {
            if (role == AppRole.BRIDGE) it.bridgeServiceRunning else it.receiverServiceRunning
        }
        if (role != AppRole.NONE && svcRunning) {
            val target = if (role == AppRole.BRIDGE) BridgeModeActivity::class.java
                         else ReceiverModeActivity::class.java
            startActivity(android.content.Intent(this, target))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val lastCrash = remember { CrashLog.last(this) }
            ModeSelectionScreen(
                lastCrash = lastCrash,
                onBridgeClick = {
                    // BUG-141 FIX: persist the role so BootReceivers only ever auto-start the
                    // service for this device's chosen mode (single-APK merge fix:
                    // without this, both BridgeService and ReceiverService could run
                    // in the same process and collide on discovery port 54322).
                    AppRoleStore.set(this, AppRole.BRIDGE)
                    // BUG-184 FIX: stop the opposite role NOW — onCreate of the target activity
                    // is skipped on singleTask re-delivery, which let both services race for
                    // discovery port 54322 and let the bridge pair with its own receiver.
                    runCatching { stopService(Intent(this, com.inputbridge.receiver.service.ReceiverService::class.java)) }
                    startActivity(Intent(this, BridgeModeActivity::class.java))
                },
                onReceiverClick = {
                    AppRoleStore.set(this, AppRole.RECEIVER)
                    // BUG-184 FIX: see onBridgeClick — stop the opposite role immediately.
                    runCatching { stopService(Intent(this, com.inputbridge.bridge.service.BridgeService::class.java)) }
                    startActivity(Intent(this, ReceiverModeActivity::class.java))
                },
            )
        }
    }
}

@Composable
fun ModeSelectionScreen(
    onBridgeClick: () -> Unit,
    onReceiverClick: () -> Unit,
    lastCrash: String? = null,
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
            if (lastCrash != null) {
                // BUG-156: show the last recorded crash here so it can be read after a
                // relaunch without a computer/ADB. Overwritten on the next crash.
                Spacer(Modifier.padding(top = 12.dp))
                Text(
                    text = "⚠ Last crash:\n$lastCrash",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
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