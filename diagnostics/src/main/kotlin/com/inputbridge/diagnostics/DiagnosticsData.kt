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

    // ── Bluetooth HID (Phase 6) ───────────────────────────────────────────────
    /** True when a Bluetooth host is actively connected to the HID device role. */
    val btConnected: Boolean = false,
    /** Display name + address of the connected BT host, or "" if none. */
    val btDeviceName: String = "",

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

    // ── Pairing ───────────────────────────────────────────────────────────────
    /** Whether the bridge and receiver have completed the pairing handshake. */
    val isPaired: Boolean = false,
    /** 6-digit session PIN displayed on the receiver for the bridge user to enter. */
    val sessionPin: String = "",
    /** IP of the peer device (bridge's target or receiver's paired source). */
    val pairedPeerIp: String = "",

    // ── Reconnect ─────────────────────────────────────────────────────────────
    /** True while the bridge is running an exponential-backoff reconnect loop. */
    val isReconnecting: Boolean = false,
    val lastReconnectAttempt: Long = 0L,
    val reconnectAttempts: Int = 0,

    // ── Packet loss ───────────────────────────────────────────────────────────
    /** Packets estimated dropped on the receiver side via sequence-number gaps. */
    val packetsDroppedSequence: Long = 0L,

    // ── Latency trace (per-stage measurements) ────────────────────────────────
    /**
     * Rolling average of the last 10 PING/PONG round-trip samples (ms).
     * More stable than [latencyMs] which is the most recent sample.
     */
    val latencyAvgMs: Long = 0L,
    /**
     * Bridge: time from InputEvent emission to UdpTransport.send() return (microseconds).
     * Measures serialization + socket-write cost on the hot path.
     */
    val captureToSendUs: Long = 0L,
    /**
     * Receiver: time from incomingPackets.collect callback to AccessibilityCommandBus
     * handleEvent() return (microseconds). Measures deserialize + injection cost.
     */
    val receiveToInjectUs: Long = 0L,

    // ── Accessibility injection state (receiver) ──────────────────────────────
    /**
     * True when the last injection attempt was blocked by a secure window
     * (e.g. lock-screen PIN entry). Cleared as soon as injection succeeds again.
     */
    val isSecureWindow: Boolean = false,
    /** Most recent accessibility injection exception message, if any. */
    val lastInjectionError: String? = null,

    // ── Errors ────────────────────────────────────────────────────────────────
    val lastError: String? = null,

    // ── Timestamp ────────────────────────────────────────────────────────────
    val snapshotTimestampMs: Long = System.currentTimeMillis(),
)
