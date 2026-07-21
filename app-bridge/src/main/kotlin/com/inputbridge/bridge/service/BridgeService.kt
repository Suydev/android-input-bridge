package com.inputbridge.bridge.service

import android.app.*
import android.content.*
import android.hardware.usb.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.ui.MainActivity
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.input.UsbInputCapture
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.protocol.PacketSerializer
import com.inputbridge.protocol.PacketType
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BridgeService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "bridge_service"
private const val COUNTER_FLUSH_INTERVAL_MS = 1_000L
private const val PING_INTERVAL_MS = 1_000L
private const val PONG_TIMEOUT_MS = 10_000L   // no PONG for this long → reconnect
private const val WATCHDOG_CHECK_MS = 3_000L
private const val WATCHDOG_GRACE_MS = 15_000L  // wait before first watchdog check
private const val PAIR_TIMEOUT_MS = 10_000L    // wait this long for PAIR_RESPONSE

/**
 * Foreground service that owns the USB input capture and UDP transport pipeline.
 *
 * Lifecycle:
 * 1. startForegroundService() → onCreate → onStartCommand → startPipeline()
 *    - Connects UdpTransport (sender mode)
 *    - Registers incoming-packet collector (handles PAIR_RESPONSE + PONG)
 *    - Performs pairing handshake if PIN is configured and not yet paired
 *    - Starts PING/PONG keep-alive loop (every 1 s)
 *    - Starts watchdog (reconnects automatically on PONG timeout)
 *    - Registers dynamic BroadcastReceiver for USB events
 *    - Checks for pre-attached USB devices
 * 2. USB device attached → request permission (if needed) → startCapture()
 *    - UsbInputCapture emits InputEvents on IO thread
 *    - Events flow through EventPacketFactory → UdpTransport.send()
 * 3. Watchdog detects PONG timeout → triggerReconnect() → exponential backoff
 * 4. ACTION_STOP intent → stopSelf() → onDestroy → full cleanup
 *
 * Idempotency: startPipeline() is guarded by [pipelineStarted] so repeated
 * onStartCommand calls (e.g. BootReceiver firing while already running) are no-ops.
 */
class BridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var usbManager: UsbManager
    private lateinit var prefs: BridgePreferences

    private val packetFactory = EventPacketFactory()
    private var udpTransport: UdpTransport? = null
    private var usbCapture: UsbInputCapture? = null
    private var captureJob: Job? = null
    private var counterFlushJob: Job? = null
    private var pingJob: Job? = null
    private var pongResponseJob: Job? = null
    private var watchdogJob: Job? = null

    /** Timestamp when the last PING was sent. */
    @Volatile private var lastPingSentAtMs = 0L
    /** Timestamp when the last PONG was received. 0 = none received yet. */
    @Volatile private var lastPongReceivedMs = 0L

    /**
     * Completes when a PAIR_RESPONSE arrives. Reset to a new instance before
     * each pairing attempt (on initial connect and on reconnect).
     */
    private var pairResponseDeferred = CompletableDeferred<Boolean>()

    /**
     * Guards against duplicate pipeline starts from repeated onStartCommand calls.
     */
    private val pipelineStarted = AtomicBoolean(false)

    /**
     * Guards against concurrent reconnect loops. Only one reconnect may run at a time.
     */
    private val reconnectInProgress = AtomicBoolean(false)

    // ── USB BroadcastReceiver ─────────────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onUsbAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onUsbDetached(device)
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        BridgeLogger.i(TAG, "USB permission granted: ${device.deviceName}")
                        serviceScope.launch { startCapture(device) }
                    } else {
                        BridgeLogger.w(TAG, "USB permission denied: ${device.deviceName}")
                        updateNotification("USB permission denied — tap to open app")
                        DiagnosticsManager.update {
                            copy(usbPermissionGranted = false, lastError = "USB permission denied")
                        }
                    }
                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        prefs = BridgePreferences(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        acquireWakeLock()
        registerUsbReceiver()
        DiagnosticsManager.update { copy(bridgeServiceRunning = true) }
        BridgeLogger.i(TAG, "BridgeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BridgeLogger.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!pipelineStarted.compareAndSet(false, true)) {
            BridgeLogger.d(TAG, "onStartCommand: pipeline already starting/running — ignoring")
            return START_STICKY
        }
        serviceScope.launch { startPipeline() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbReceiver()

        // 1. Cancel tracked jobs
        counterFlushJob?.cancel()
        captureJob?.cancel()
        pingJob?.cancel()
        pongResponseJob?.cancel()
        watchdogJob?.cancel()

        // 2. Release resources in NonCancellable context
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { usbCapture?.stop() }
                runCatching { udpTransport?.disconnect() }
            }
        }
        usbCapture = null
        udpTransport = null

        // 3. Cancel scope
        serviceScope.cancel()
        pipelineStarted.set(false)
        reconnectInProgress.set(false)
        releaseWakeLock()
        DiagnosticsManager.update {
            copy(
                bridgeServiceRunning = false,
                transportConnected = false,
                inputCaptureActive = false,
                usbDeviceConnected = false,
                isReconnecting = false,
            )
        }
        BridgeLogger.i(TAG, "BridgeService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Pipeline setup ────────────────────────────────────────────────────────

    private suspend fun startPipeline() {
        val targetIp = prefs.targetIp
        val port = prefs.port

        if (targetIp.isBlank()) {
            BridgeLogger.w(TAG, "Target IP not configured — set it in Settings")
            updateNotification("Set receiver IP in Settings first")
            DiagnosticsManager.update { copy(lastError = "Target IP not configured") }
            pipelineStarted.set(false)
            return
        }

        val config = TransportConfig(targetIp = targetIp, port = port)
        val transport = UdpTransport(config, isSender = true)
        udpTransport = transport

        if (!transport.connect()) {
            BridgeLogger.w(TAG, "UDP connect failed ($targetIp:$port)")
            updateNotification("Transport error — check Settings")
            DiagnosticsManager.update { copy(lastError = "UDP connect failed") }
            pipelineStarted.set(false)
            return
        }

        BridgeLogger.i(TAG, "UDP transport ready → $targetIp:$port")
        DiagnosticsManager.update { copy(transportConnected = true, targetIp = targetIp) }

        // Register incoming-packet collector BEFORE sending any packet, so
        // PAIR_RESPONSE is never missed even under very low latency.
        pairResponseDeferred = CompletableDeferred()
        startIncomingLoop(transport)

        // Pairing: only attempt if a PIN is configured AND not already paired.
        if (prefs.pairingPin.isNotEmpty() && !prefs.isPaired) {
            val paired = doPairing(transport)
            if (!paired) {
                // Pairing failed — leave pipelineStarted = true so the service
                // stays alive (user can fix PIN in Settings and restart).
                return
            }
        } else if (prefs.isPaired) {
            BridgeLogger.i(TAG, "Already paired — skipping pairing handshake")
            DiagnosticsManager.update { copy(isPaired = true, pairedPeerIp = targetIp) }
            updateNotification("Ready (paired) — waiting for USB device…")
        } else {
            BridgeLogger.i(TAG, "No PIN configured — connecting in open mode (no pairing)")
            updateNotification("Ready — waiting for USB device…")
        }

        // Diagnostics counter flush
        counterFlushJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                DiagnosticsManager.flushCounters()
            }
        }

        startPingLoop(transport)
        startWatchdog()

        // Handle USB devices already attached before service started
        val preAttached = usbManager.deviceList.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID
            }
        }
        if (preAttached != null) {
            BridgeLogger.i(TAG, "Pre-attached HID device: ${preAttached.deviceName}")
            onUsbAttached(preAttached)
        }
    }

    // ── Incoming-packet loop ──────────────────────────────────────────────────

    /**
     * Collect ALL packets arriving from the receiver in a single coroutine.
     * Must be started BEFORE any PAIR_REQUEST or PING is sent, so no packet
     * is missed due to a collection-start race.
     *
     * Handles:
     * - PAIR_RESPONSE: completes [pairResponseDeferred]
     * - PONG: measures round-trip latency, resets watchdog timer
     */
    private fun startIncomingLoop(transport: UdpTransport) {
        pongResponseJob = serviceScope.launch {
            transport.incomingPackets.collect { packet ->
                when (packet.type) {
                    PacketType.PAIR_RESPONSE -> {
                        val accepted = PacketSerializer.parsePairResponseAccepted(packet.payload)
                        BridgeLogger.i(TAG, "PAIR_RESPONSE received: accepted=$accepted")
                        if (!pairResponseDeferred.isCompleted) {
                            pairResponseDeferred.complete(accepted)
                        }
                    }
                    PacketType.PONG -> {
                        val sentAt = lastPingSentAtMs
                        if (sentAt > 0L) {
                            val latency = System.currentTimeMillis() - sentAt
                            if (latency in 0L..10_000L) {
                                lastPongReceivedMs = System.currentTimeMillis()
                                DiagnosticsManager.recordLatency(latency)
                                BridgeLogger.d(TAG, "PONG received — latency=${latency}ms")
                            }
                        }
                    }
                    else -> Unit  // future receiver→bridge control packets
                }
            }
        }
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    /**
     * Send a PAIR_REQUEST with the user's PIN and wait up to [PAIR_TIMEOUT_MS]
     * for the receiver to accept or reject.
     *
     * Returns true if pairing succeeded (or if no PIN is configured),
     * false if rejected or timed out.
     */
    private suspend fun doPairing(transport: UdpTransport): Boolean {
        val pin = prefs.pairingPin
        updateNotification("Pairing — waiting for receiver…")
        DiagnosticsManager.update { copy(isPaired = false) }

        transport.send(packetFactory.makePairRequest(pin))
        BridgeLogger.i(TAG, "PAIR_REQUEST sent (pin=****)")

        val accepted = withTimeoutOrNull(PAIR_TIMEOUT_MS) {
            pairResponseDeferred.await()
        } ?: false

        return if (accepted) {
            prefs.isPaired = true
            transport.send(packetFactory.makePairConfirm())
            DiagnosticsManager.update { copy(isPaired = true, pairedPeerIp = prefs.targetIp) }
            BridgeLogger.i(TAG, "Pairing confirmed")
            updateNotification("Paired — waiting for USB device…")
            true
        } else {
            BridgeLogger.w(TAG, "Pairing rejected or timed out")
            updateNotification("Pairing failed — check PIN in Settings")
            DiagnosticsManager.update {
                copy(isPaired = false, lastError = "Pairing failed — check PIN matches receiver display")
            }
            false
        }
    }

    // ── PING keep-alive ───────────────────────────────────────────────────────

    private fun startPingLoop(transport: UdpTransport) {
        pingJob = serviceScope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                val ping = packetFactory.makePing()
                lastPingSentAtMs = System.currentTimeMillis()
                DiagnosticsManager.update { copy(lastPingSentMs = lastPingSentAtMs) }
                transport.send(ping)
                BridgeLogger.d(TAG, "PING sent (seq=${ping.sequenceNo})")
            }
        }
    }

    // ── Watchdog + reconnect ──────────────────────────────────────────────────

    /**
     * Periodically checks whether PONGs are still arriving.
     * If no PONG for [PONG_TIMEOUT_MS] after the grace period, triggers reconnect.
     */
    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            delay(WATCHDOG_GRACE_MS)  // wait for initial connection to settle
            while (isActive) {
                delay(WATCHDOG_CHECK_MS)
                val now = System.currentTimeMillis()
                val lastPong = lastPongReceivedMs
                // Only fire if we've seen at least one PONG (i.e. receiver was reachable)
                if (lastPong > 0L && (now - lastPong) > PONG_TIMEOUT_MS) {
                    BridgeLogger.w(TAG, "Watchdog: no PONG for ${now - lastPong}ms — triggering reconnect")
                    launch { triggerReconnect() }
                    break
                }
            }
        }
    }

    /**
     * Exponential-backoff reconnect loop.
     * Attempts: 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, … (up to 10 attempts).
     * Guarded by [reconnectInProgress] so only one loop runs at a time.
     */
    private suspend fun triggerReconnect() {
        if (!reconnectInProgress.compareAndSet(false, true)) return

        // Cancel currently running I/O jobs (but keep serviceScope alive)
        pingJob?.cancel();        pingJob = null
        pongResponseJob?.cancel(); pongResponseJob = null
        watchdogJob?.cancel();    watchdogJob = null
        runCatching { udpTransport?.disconnect() }
        udpTransport = null
        lastPingSentAtMs = 0L
        lastPongReceivedMs = 0L

        DiagnosticsManager.update { copy(transportConnected = false, isReconnecting = true) }
        updateNotification("Reconnecting…")

        val targetIp = prefs.targetIp
        val port = prefs.port
        val backoffs = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000, 30_000, 30_000)

        for (backoffMs in backoffs) {
            if (!serviceScope.isActive) break
            DiagnosticsManager.recordReconnectAttempt()
            BridgeLogger.i(TAG, "Reconnect: waiting ${backoffMs}ms before next attempt")
            delay(backoffMs)
            if (!serviceScope.isActive) break

            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)

            if (transport.connect()) {
                udpTransport = transport
                DiagnosticsManager.update { copy(transportConnected = true, isReconnecting = false) }
                updateNotification("Reconnected → $targetIp:$port")
                BridgeLogger.i(TAG, "Reconnect successful")

                // Fresh deferred for new pairing attempt if needed
                pairResponseDeferred = CompletableDeferred()
                startIncomingLoop(transport)

                if (!prefs.isPaired && prefs.pairingPin.isNotEmpty()) {
                    val paired = doPairing(transport)
                    if (!paired) {
                        BridgeLogger.e(TAG, "Re-pairing failed after reconnect")
                        runCatching { transport.disconnect() }
                        reconnectInProgress.set(false)
                        return
                    }
                }

                startPingLoop(transport)
                startWatchdog()
                reconnectInProgress.set(false)
                return
            }
            BridgeLogger.w(TAG, "Reconnect attempt failed to $targetIp:$port")
        }

        // All attempts exhausted
        DiagnosticsManager.update {
            copy(isReconnecting = false, lastError = "Reconnect failed after ${backoffs.size} attempts")
        }
        updateNotification("Reconnect failed — restart bridge manually")
        reconnectInProgress.set(false)
    }

    // ── USB device lifecycle ──────────────────────────────────────────────────

    private fun onUsbAttached(device: UsbDevice) {
        BridgeLogger.i(TAG, "USB HID device attached: ${device.deviceName}")
        DiagnosticsManager.update { copy(usbDeviceName = device.deviceName) }

        if (usbManager.hasPermission(device)) {
            serviceScope.launch { startCapture(device) }
        } else {
            requestUsbPermission(device)
            updateNotification("Tap to grant USB permission")
        }
    }

    private fun onUsbDetached(device: UsbDevice) {
        BridgeLogger.i(TAG, "USB device detached: ${device.deviceName}")
        serviceScope.launch { stopCapture() }
        DiagnosticsManager.update {
            copy(usbDeviceConnected = false, usbDeviceName = "None", inputCaptureActive = false)
        }
        updateNotification(if (prefs.isPaired) "Paired — USB disconnected" else "USB device disconnected")
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pi)
        BridgeLogger.i(TAG, "USB permission requested for ${device.deviceName}")
    }

    private suspend fun startCapture(device: UsbDevice) {
        stopCapture()

        val capture = UsbInputCapture(this, device)
        usbCapture = capture

        if (!capture.start()) {
            BridgeLogger.e(TAG, "UsbInputCapture failed to start for ${device.deviceName}")
            DiagnosticsManager.update {
                copy(inputCaptureActive = false, lastError = "USB capture start failed")
            }
            updateNotification("USB error — replug the receiver")
            return
        }

        DiagnosticsManager.update {
            copy(usbDeviceConnected = true, usbPermissionGranted = true, inputCaptureActive = true)
        }
        updateNotification("Bridging — ${device.deviceName}")
        BridgeLogger.i(TAG, "USB capture active for ${device.deviceName}")

        captureJob = serviceScope.launch {
            capture.events.collect { event ->
                val packet = packetFactory.fromEvent(event) ?: return@collect
                val sent = udpTransport?.send(packet) ?: false
                if (sent) DiagnosticsManager.onPacketSent()
                else DiagnosticsManager.onSendFailed()
            }
        }
    }

    private suspend fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        usbCapture?.stop()
        usbCapture = null
    }

    // ── USB receiver registration ─────────────────────────────────────────────

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun unregisterUsbReceiver() {
        runCatching { unregisterReceiver(usbReceiver) }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("InputBridge Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Bridge Service", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "Keeps the USB input bridge alive"
                setShowBadge(false)
            }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InputBridge::BridgeWakeLock",
        ).also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.inputbridge.bridge.ACTION_STOP"
        private const val ACTION_USB_PERMISSION = "com.inputbridge.bridge.USB_PERMISSION"
    }
}
