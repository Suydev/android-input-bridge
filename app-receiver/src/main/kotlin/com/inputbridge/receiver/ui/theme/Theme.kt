package com.inputbridge.receiver.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ReceiverBackground = Color(0xFF0A0A0A)
val ReceiverSurface    = Color(0xFF141414)
val ReceiverOnSurface  = Color(0xFFE0E0E0)
val ReceiverPrimary    = Color(0xFF00AAFF)   // blue accent for receiver
val ReceiverError      = Color(0xFFFF4444)
val ReceiverWarning    = Color(0xFFFFAA00)
val ReceiverDim        = Color(0xFF555555)

private val DarkColorScheme = darkColorScheme(
    primary      = ReceiverPrimary,
    onPrimary    = Color.Black,
    background   = ReceiverBackground,
    surface      = ReceiverSurface,
    onBackground = ReceiverOnSurface,
    onSurface    = ReceiverOnSurface,
    error        = ReceiverError,
)

@Composable
fun ReceiverTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography(), content = content)
}
