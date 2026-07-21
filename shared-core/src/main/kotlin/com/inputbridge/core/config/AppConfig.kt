package com.inputbridge.core.config

/**
 * Top-level app configuration, shared between bridge and receiver.
 * Values are loaded from SharedPreferences (DataStore migration: Phase 7).
 */
data class AppConfig(
    val transport: TransportConfig = TransportConfig(),
    val mouse: MouseConfig = MouseConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val security: SecurityConfig = SecurityConfig(),
)

data class TransportConfig(
    /** Preferred transport mode. */
    val mode: TransportMode = TransportMode.UDP,
    /** Port for UDP/TCP communication. */
    val port: Int = 54321,
    /** Target device IP (bridge side: where to send; receiver side: where to listen). */
    val targetIp: String = "",
    /** Timeout before reconnect attempt in ms. */
    val reconnectTimeoutMs: Long = 3_000L,
    /** Maximum reconnect attempts before giving up. */
    val maxReconnectAttempts: Int = 10,
    /** Keep-alive interval in ms. */
    val keepAliveIntervalMs: Long = 1_000L,
)

data class MouseConfig(
    /** Pointer sensitivity multiplier. */
    val sensitivity: Float = 1.0f,
    /** Scroll sensitivity multiplier. */
    val scrollSensitivity: Float = 1.0f,
    /** Enable pointer acceleration. */
    val acceleration: Boolean = false,
    /** Double-click threshold in ms. */
    val doubleClickThresholdMs: Long = 300L,
)

data class DisplayConfig(
    /** Enable black screen mode on the bridge device (hides UI, dims to minimum brightness). */
    val blackScreenMode: Boolean = false,
    /** Show latency overlay in the bridge UI. */
    val showLatencyOverlay: Boolean = true,
    /** Keep screen on while bridge/receiver is active. */
    val keepScreenOn: Boolean = true,
    /**
     * Screen brightness override.
     * -1f = follow system default.
     *  0f = minimum (but backlight still on).
     *  1f = maximum.
     */
    val screenBrightness: Float = -1f,
    /** Auto-start the service on device boot. */
    val autoStartOnBoot: Boolean = true,
    /**
     * Show a floating cursor-position dot overlay (receiver only).
     * Requires SYSTEM_ALERT_WINDOW / canDrawOverlays permission.
     */
    val showCursorOverlay: Boolean = false,
)

data class SecurityConfig(
    /** Shared pairing token (hex string, 16 bytes). Empty = not paired. */
    val pairingToken: String = "",
    /** Trusted peer device name. */
    val trustedDeviceName: String = "",
)

enum class TransportMode(val id: Int) {
    /** UDP over same LAN or hotspot — default, lowest latency. */
    UDP(0),
    /** Wi-Fi Direct peer-to-peer (stub — Phase 8). */
    WIFI_DIRECT(1),
    /** TCP over local network (stub — Phase 8). */
    TCP(2),
    /** Bluetooth HID (bridge → tablet system cursor). */
    BLUETOOTH_HID(3);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: UDP
    }
}
