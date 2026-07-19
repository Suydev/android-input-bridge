package com.inputbridge.receiver.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.inputbridge.accessibility.AccessibilityCommandBus
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.protocol.PacketToEventConverter
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.ui.MainActivity
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*

private const val TAG = "ReceiverService"
private const val NOTIFICATION_ID = 2001
private const val CHANNEL_ID = "receiver_service"
private const val COUNTER_FLUSH_INTERVAL_MS = 1_000L

/**
 * Foreground service that owns the UDP receive loop and accessibility dispatch pipeline.
 *
 * Lifecycle:
 * 1. startForegroundService() → onCreate → onStartCommand → startListening()
 *    - Binds UdpTransport to the configured port (default 54321)
 *    - Collects incoming Packet objects from UdpTransport.incomingPackets
 *    - Converts each Packet → InputEvent via PacketToEventConverter
 *    - Posts InputEvents to AccessibilityCommandBus for injection
 * 2. ACTION_STOP → stopSelf() → onDestroy → disconnect transport + release WakeLock
 *
 * Idempotency: startListening() is guarded by [listenerStarted] so repeated
 * onStartCommand calls are no-ops.
 *
 * Teardown: individual jobs are cancelled first, then the socket is closed in
 * a NonCancellable context before serviceScope is cancelled.
 */
class ReceiverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var prefs: ReceiverPreferences
    private var udpTransport: UdpTransport? = null
    private var receiveJob: Job? = null
    private var counterFlushJob: Job? = null

    /** Guards against duplicate listener starts from repeated onStartCommand calls. */
    @Volatile private var listenerStarted = false

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BridgeLogger.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }
        // Guard: ignore repeated starts (e.g. BootReceiver on already-running service)
        if (listenerStarted) {
            BridgeLogger.d(TAG, "onStartCommand: listener already running, ignoring")
            return START_STICKY
        }
        serviceScope.launch { startListening() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 1. Cancel tracked jobs to stop new work immediately
        receiveJob?.cancel()
        counterFlushJob?.cancel()

        // 2. Disconnect the UDP socket in NonCancellable context so the port is
        //    released even though serviceScope will be cancelled next.
        //    runBlocking is acceptable: onDestroy is on the main thread,
        //    disconnect() is fast (cancel receive loop + close DatagramSocket).
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { udpTransport?.disconnect() }
            }
        }
        udpTransport = null

        // 3. Cancel the scope after resources are freed
        serviceScope.cancel()
        listenerStarted = false
        releaseWakeLock()
        DiagnosticsManager.update { copy(receiverServiceRunning = false, transportConnected = false) }
        BridgeLogger.i(TAG, "ReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Receive pipeline ──────────────────────────────────────────────────────

    private suspend fun startListening() {
        listenerStarted = true
        val port = prefs.port
        val config = TransportConfig(port = port)
        val transport = UdpTransport(config, isSender = false)
        udpTransport = transport

        if (!transport.connect()) {
            BridgeLogger.e(TAG, "Failed to bind UDP socket on port $port")
            updateNotification("UDP bind failed on port $port")
            DiagnosticsManager.update { copy(lastError = "UDP bind failed on port $port") }
            listenerStarted = false  // allow retry on next start
            return
        }

        BridgeLogger.i(TAG, "Listening for packets on UDP port $port")
        updateNotification("Listening on UDP :$port")
        DiagnosticsManager.update { copy(transportConnected = true) }

        // Periodic diagnostics counter flush (1 s interval)
        counterFlushJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                DiagnosticsManager.flushCounters()
            }
        }

        // Hot path: Packet → InputEvent → AccessibilityCommandBus
        receiveJob = serviceScope.launch {
            transport.incomingPackets.collect { packet ->
                DiagnosticsManager.onPacketReceived()
                val event = PacketToEventConverter.toInputEvent(packet) ?: return@collect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    AccessibilityCommandBus.post(event)
                } else {
                    BridgeLogger.w(TAG, "Android N+ required for accessibility injection — skipping")
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
            .setSmallIcon(android.R.drawable.ic_menu_receive)
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
