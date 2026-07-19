package com.inputbridge.diagnostics

/**
 * Snapshot of all diagnostic information, collected from all subsystems.
 * Emitted as a StateFlow for the UI to observe.
 */
data class DiagnosticsData(
    // ── Service state ─────────────────────────────────────────────────────────
    val bridgeServiceRunning: Boolean = false,
    val receiverServiceRunning: Boolean = false,

    // ── Input capture ─────────────────────────────────────────────────────────
    val usbDeviceConnected: Boolean = false,
    val usbDeviceName: String = "None",
    val inputCaptureActive: Boolean = false,

    // ── Transport ─────────────────────────────────────────────────────────────
    val transportMode: String = "UDP",
    val transportConnected: Boolean = false,
    val targetIp: String = "",
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val packetsSendFailed: Long = 0L,

    // ── Latency ───────────────────────────────────────────────────────────────
    val latencyMs: Long = 0L,
    val lastPingSentMs: Long = 0L,
    val lastPongReceivedMs: Long = 0L,

    // ── Accessibility (receiver) ──────────────────────────────────────────────
    val accessibilityEnabled: Boolean = false,
    val accessibilityMode: String = "None", // "Accessibility" | "HID"

    // ── Permissions ───────────────────────────────────────────────────────────
    val usbPermissionGranted: Boolean = false,
    val accessibilityPermissionGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val overlayPermissionGranted: Boolean = false,

    // ── Errors ────────────────────────────────────────────────────────────────
    val lastError: String? = null,
    val lastReconnectAttempt: Long = 0L,
    val reconnectAttempts: Int = 0,

    // ── Timestamp ────────────────────────────────────────────────────────────
    val snapshotTimestampMs: Long = System.currentTimeMillis(),
)
