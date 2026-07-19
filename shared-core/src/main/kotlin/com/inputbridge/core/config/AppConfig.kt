package com.inputbridge.core.config

/**
 * Top-level app configuration, shared between bridge and receiver.
 * Values are loaded from DataStore and exposed via ConfigRepository.
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
    /** Enable black screen mode on the bridge device. */
    val blackScreenMode: Boolean = false,
    /** Show latency overlay in the bridge UI. */
    val showLatencyOverlay: Boolean = true,
    /** Keep screen on while bridge is active. */
    val keepScreenOn: Boolean = true,
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
    /** Wi-Fi Direct peer-to-peer. */
    WIFI_DIRECT(1),
    /** TCP over local network. */
    TCP(2),
    /** Bluetooth HID (bridge → tablet system cursor). */
    BLUETOOTH_HID(3);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: UDP
    }
}
