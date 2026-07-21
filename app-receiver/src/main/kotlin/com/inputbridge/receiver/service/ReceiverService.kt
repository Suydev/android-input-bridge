package com.inputbridge.receiver.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.inputbridge.accessibility.AccessibilityCommandBus
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.protocol.PacketSerializer
import com.inputbridge.protocol.PacketToEventConverter
import com.inputbridge.protocol.PacketType
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.ui.MainActivity
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ReceiverService"
private const val NOTIFICATION_ID = 2001
private const val CHANNEL_ID = "receiver_service"
private const val COUNTER_FLUSH_INTERVAL_MS = 1_000L
private const val BRIDGE_WATCHDOG_CHECK_MS  = 5_000L  // how often the watchdog polls
private const val BRIDGE_SILENCE_TIMEOUT_MS = 15_000L // silence > this → notify user

/**
 * Foreground service that owns the UDP receive loop and accessibility dispatch pipeline.
 *
 * Lifecycle:
 * 1. startForegroundService() → onCreate → onStartCommand → startListening()
 *    - Ensures a session PIN exists in prefs (generates one if missing)
 *    - Applies persisted mouse sensitivity to AccessibilityCommandBus
 *    - Binds UdpTransport to the configured port
 *    - Collects incoming Packet objects from UdpTransport.incomingPackets
 *
 * Packet handling:
 *    - PAIR_REQUEST: validate PIN → send PAIR_RESPONSE → record paired bridge IP
 *    - PAIR_CONFIRM: log (pairing complete on both sides)
 *    - PING: respond with PONG
 *    - KEEP_ALIVE: log, no-op
 *    - DISCONNECT: clear pairing, update status
 *    - Input events: source validation → PacketToEventConverter → AccessibilityCommandBus
 *
 * Source validation:
 *    After pairing, input packets from any IP other than the paired bridge are dropped.
 *    PAIR_REQUEST packets are always accepted (to allow re-pairing after IP change).
 *
 * Packet loss detection:
 *    Sequence number gaps in input event packets are counted as dropped packets.
 *
 * Idempotency: guarded by [listenerStarted] AtomicBoolean.
 */
class ReceiverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var prefs: ReceiverPreferences
    private var udpTransport: UdpTransport? = null
    private var receiveJob: Job? = null
    private var counterFlushJob: Job? = null
    private var watchdogJob: Job? = null

    private val packetFactory = EventPacketFactory()

    /**
     * Guards against duplicate listener starts from repeated onStartCommand calls.
     */
    private val listenerStarted = AtomicBoolean(false)

    // ── Source validation ─────────────────────────────────────────────────────

    /** IP of the bridge device that completed pairing. Empty = not paired. */
    @Volatile private var pairedBridgeIp: String = ""

    // ── Packet loss detection ─────────────────────────────────────────────────

    /**
     * Monotonic time of the last received PING from the bridge (System.currentTimeMillis()).
     * 0 = no PING received yet this session.
     * Used by the bridge-silence watchdog (BUG-041 fix).
     */
    @Volatile private var lastPingReceivedMs = 0L

    /**
     * Whether the watchdog has already fired a "bridge silent" notification.
     * Avoids spamming the notification every 5s once the bridge goes silent.
     */
    @Volatile private var bridgeSilenceNotified = false

    /** Last input-event sequence number seen. -1 = no packets yet. */
    private var lastInputSeqNo = -1

    /** Running count of estimated dropped packets (sequence gaps in input events). */
    private val droppedSequencePackets = AtomicLong(0)

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = ReceiverPreferences(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        acquireWakeLock()
        DiagnosticsManager.update { copy(receiverServiceRunning = true) }
        BridgeLogger.i(TAG, "ReceiverService created")
    }

    // ── Phase 7 — cursor overlay lifecycle ────────────────────────────────────

    /**
     * Start the cursor dot overlay service if the user has enabled it and the
     * required SYSTEM_ALERT_WINDOW permission is granted.
     * Called after UDP transport connects successfully so the cursor is live
     * before any events arrive.
     */
    private fun startCursorOverlayIfNeeded() {
        if (!prefs.showCursorOverlay) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            BridgeLogger.w(TAG, "Cursor overlay requested but canDrawOverlays() is false — skipping")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            BridgeLogger.i(TAG, "Starting cursor overlay service")
            startService(android.content.Intent(this, CursorOverlayService::class.java))
        }
    }

    private fun stopCursorOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopService(android.content.Intent(this, CursorOverlayService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BridgeLogger.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listenerStarted.compareAndSet(false, true)) {
            BridgeLogger.d(TAG, "onStartCommand: listener already starting/running — ignoring")
            return START_STICKY
        }
        serviceScope.launch { startListening() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 1. Cancel tracked jobs
        receiveJob?.cancel()
        counterFlushJob?.cancel()
        watchdogJob?.cancel()

        // 2. Release UDP socket in NonCancellable context
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { udpTransport?.disconnect() }
            }
        }
        udpTransport = null

        // 3. Cancel scope
        serviceScope.cancel()
        listenerStarted.set(false)
        releaseWakeLock()

        // Phase 7: tear down cursor overlay
        stopCursorOverlay()

        DiagnosticsManager.update {
            copy(receiverServiceRunning = false, transportConnected = false, isReconnecting = false)
        }
        BridgeLogger.i(TAG, "ReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Receive pipeline ──────────────────────────────────────────────────────

    private suspend fun startListening() {
        // Apply persisted sensitivity before receiving any events.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AccessibilityCommandBus.setSensitivity(prefs.mouseSensitivity)
        }

        // Ensure a session PIN exists; generate one if this is first run.
        if (prefs.sessionPin.isEmpty()) {
            prefs.generateNewPin()
        }
        val sessionPin = prefs.sessionPin

        // Load existing pairing state
        pairedBridgeIp = prefs.pairedBridgeIp
        DiagnosticsManager.update {
            copy(
                sessionPin = sessionPin,
                isPaired = prefs.isPaired,
                pairedPeerIp = pairedBridgeIp,
            )
        }

        val port = prefs.port
        val config = TransportConfig(port = port)
        val transport = UdpTransport(config, isSender = false)
        udpTransport = transport

        if (!transport.connect()) {
            BridgeLogger.e(TAG, "Failed to bind UDP socket on port $port")
            updateNotification("UDP bind failed on port $port")
            DiagnosticsManager.update { copy(lastError = "UDP bind failed on port $port") }
            listenerStarted.set(false)
            return
        }

        BridgeLogger.i(TAG, "Listening for packets on UDP port $port (PIN=$sessionPin)")
        updateNotification("Listening on UDP :$port — PIN: $sessionPin")
        DiagnosticsManager.update { copy(transportConnected = true) }

        // Phase 7: start cursor overlay now that the transport is live
        startCursorOverlayIfNeeded()

        // Periodic diagnostics flush (includes sequence drop count)
        counterFlushJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                DiagnosticsManager.flushCounters()
                val dropped = droppedSequencePackets.get()
                if (dropped > 0L) {
                    DiagnosticsManager.update { copy(packetsDroppedSequence = dropped) }
                }
                // Flush accessibility inject latency from the command bus timing loop.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val injectUs = AccessibilityCommandBus.getLastInjectUs()
                    if (injectUs > 0L) {
                        DiagnosticsManager.update { copy(receiveToInjectUs = injectUs) }
                    }
                }
            }
        }

        // BUG-041 fix: bridge-silence watchdog.
        // If the bridge sends a PING every 1 s, we should see one within ~3 s under normal
        // conditions. We wait BRIDGE_SILENCE_TIMEOUT_MS (15 s) before alerting the user,
        // which gives plenty of headroom for temporary Wi-Fi congestion or reconnect pauses.
        // The watchdog only activates once at least one PING has been received, so it does
        // not fire during initial pairing or while waiting for the first connection.
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(BRIDGE_WATCHDOG_CHECK_MS)
                val firstPing = lastPingReceivedMs
                if (firstPing == 0L) continue  // haven't seen a PING yet — bridge may still be connecting
                val silenceMs = System.currentTimeMillis() - firstPing
                if (silenceMs >= BRIDGE_SILENCE_TIMEOUT_MS && !bridgeSilenceNotified) {
                    bridgeSilenceNotified = true
                    val silenceSec = silenceMs / 1000
                    BridgeLogger.w(TAG, "Bridge silence detected: no PING for ${silenceSec}s")
                    updateNotification("Bridge silent for ${silenceSec}s — check connection")
                    DiagnosticsManager.update {
                        copy(
                            lastError = "No PING from bridge for ${silenceSec}s — bridge may have stopped",
                            transportConnected = false,
                        )
                    }
                }
            }
        }

        // Hot receive loop
        receiveJob = serviceScope.launch {
            transport.incomingPackets.collect { packet ->
                DiagnosticsManager.onPacketReceived()

                // ── Source validation ─────────────────────────────────────────
                // Accept PAIR_REQUEST from any IP (allows re-pairing after bridge restarts).
                // All other packet types are dropped if they come from an unknown sender.
                val senderIp = transport.getLastSenderIp()
                if (pairedBridgeIp.isNotEmpty() &&
                    senderIp != pairedBridgeIp &&
                    packet.type != PacketType.PAIR_REQUEST) {
                    BridgeLogger.d(
                        TAG,
                        "Dropping ${packet.type} from unknown sender $senderIp (paired=$pairedBridgeIp)"
                    )
                    return@collect
                }

                // ── Control packet handling ───────────────────────────────────
                when (packet.type) {

                    PacketType.PAIR_REQUEST -> {
                        val receivedPin = PacketSerializer.parsePairRequestPin(packet.payload)
                        val accepted = receivedPin == prefs.sessionPin
                        BridgeLogger.i(TAG, "PAIR_REQUEST from $senderIp — accepted=$accepted")
                        transport.send(packetFactory.makePairResponse(accepted))
                        if (accepted && senderIp != null) {
                            pairedBridgeIp = senderIp
                            prefs.pairedBridgeIp = senderIp
                            prefs.isPaired = true
                            DiagnosticsManager.update {
                                copy(isPaired = true, pairedPeerIp = senderIp)
                            }
                            updateNotification("Paired with bridge ($senderIp)")
                        } else {
                            BridgeLogger.w(TAG, "Pairing rejected — wrong PIN")
                        }
                    }

                    PacketType.PAIR_CONFIRM -> {
                        BridgeLogger.i(TAG, "PAIR_CONFIRM received — pairing complete on both sides")
                    }

                    PacketType.PING -> {
                        val pong = packetFactory.makePong(packet.sequenceNo)
                        transport.send(pong)
                        BridgeLogger.d(TAG, "PING → PONG (seq=${packet.sequenceNo})")
                        // BUG-041 fix: record the time of the last PING so the watchdog
                        // knows the bridge is alive. Also clear any previously-shown
                        // silence notification so it re-fires if the bridge goes silent again.
                        lastPingReceivedMs = System.currentTimeMillis()
                        if (bridgeSilenceNotified) {
                            bridgeSilenceNotified = false
                            // BUG-047 fix: pairedBridgeIp can be empty in open-mode sessions
                            // (no PIN set, so PAIR_REQUEST/PAIR_RESPONSE exchange never happened).
                            // Guard to avoid the notification reading "Paired with bridge ()".
                            val recoveredMsg = if (pairedBridgeIp.isNotEmpty())
                                "Paired with bridge ($pairedBridgeIp)"
                            else
                                "Bridge reconnected — PIN: $sessionPin"
                            updateNotification(recoveredMsg)
                            DiagnosticsManager.update { copy(lastError = null) }
                        }
                    }

                    PacketType.KEEP_ALIVE -> {
                        BridgeLogger.d(TAG, "KEEP_ALIVE received")
                    }

                    PacketType.DISCONNECT -> {
                        BridgeLogger.i(TAG, "DISCONNECT received from bridge — clearing pairing")
                        pairedBridgeIp = ""
                        prefs.pairedBridgeIp = ""
                        prefs.isPaired = false
                        DiagnosticsManager.update {
                            copy(transportConnected = false, isPaired = false, pairedPeerIp = "")
                        }
                        updateNotification("Bridge disconnected — PIN: $sessionPin")
                    }

                    // ── Input event packets ───────────────────────────────────
                    else -> {
                        // Packet loss detection via sequence-number gaps
                        val seq = packet.sequenceNo
                        if (lastInputSeqNo >= 0 && seq > lastInputSeqNo + 1) {
                            val gap = (seq - lastInputSeqNo - 1).toLong()
                            droppedSequencePackets.addAndGet(gap)
                            BridgeLogger.d(TAG, "Seq gap: expected ${lastInputSeqNo + 1}, got $seq (~$gap dropped)")
                        }
                        lastInputSeqNo = seq

                        val event = PacketToEventConverter.toInputEvent(packet) ?: return@collect
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            AccessibilityCommandBus.post(event)
                        } else {
                            BridgeLogger.w(TAG, "Android N+ required for accessibility injection — skipping")
                        }
                    }
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("InputBridge Receiver")
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
        val ch = NotificationChannel(CHANNEL_ID, "Receiver Service", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InputBridge::ReceiverWakeLock",
        ).also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.inputbridge.receiver.ACTION_STOP"
    }
}
