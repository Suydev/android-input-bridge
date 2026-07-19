package com.inputbridge.bridge.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Bridge UI palette: near-black with a green status accent (terminal aesthetic)
val BridgeBackground   = Color(0xFF0A0A0A)
val BridgeSurface      = Color(0xFF141414)
val BridgeOnSurface    = Color(0xFFE0E0E0)
val BridgePrimary      = Color(0xFF00FF88)   // green: active/connected
val BridgeError        = Color(0xFFFF4444)   // red: error/disconnected
val BridgeWarning      = Color(0xFFFFAA00)   // amber: warning/reconnecting
val BridgeDim          = Color(0xFF555555)   // dim text

private val DarkColorScheme = darkColorScheme(
    primary          = BridgePrimary,
    onPrimary        = Color.Black,
    secondary        = BridgeWarning,
    background       = BridgeBackground,
    surface          = BridgeSurface,
    onBackground     = BridgeOnSurface,
    onSurface        = BridgeOnSurface,
    error            = BridgeError,
)

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content,
    )
}
