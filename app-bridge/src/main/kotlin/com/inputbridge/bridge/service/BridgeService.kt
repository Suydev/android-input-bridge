package com.inputbridge.bridge.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.inputbridge.bridge.R
import com.inputbridge.bridge.ui.MainActivity
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import kotlinx.coroutines.*

private const val TAG = "BridgeService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "bridge_service"

/**
 * Foreground service that keeps the USB input capture and network transport alive.
 *
 * Lifecycle:
 * 1. startForegroundService() → onCreate → onStartCommand → starts capture + transport
 * 2. ACTION_STOP intent → stops capture + transport → stopSelf()
 * 3. Android kills service → BootReceiver can restart it if RECEIVE_BOOT_COMPLETED
 *
 * This service holds a partial WakeLock so the input pipeline continues
 * while the screen is off. The WakeLock is released when the service stops.
 *
 * Battery note: we request battery optimization exemption from the user during setup.
 * We cannot guarantee survival against aggressive ROM battery managers (MIUI, ColorOS).
 * The UI is honest about this limitation.
 */
class BridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        acquireWakeLock()
        DiagnosticsManager.update { copy(bridgeServiceRunning = true) }
        BridgeLogger.i(TAG, "BridgeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BridgeLogger.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }
        // Start the input capture + transport pipeline
        serviceScope.launch { startPipeline() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        DiagnosticsManager.update { copy(bridgeServiceRunning = false) }
        BridgeLogger.i(TAG, "BridgeService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private suspend fun startPipeline() {
        // Phase 2+: wire up UsbInputCapture → EventPacketFactory → UdpTransport
        // For Phase 1: update diagnostics and notification to show "active"
        BridgeLogger.i(TAG, "Input pipeline started (Phase 1 scaffold)")
        updateNotification("Waiting for USB device…")
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("InputBridge Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bridge Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the USB input bridge alive"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InputBridge::BridgeWakeLock"
        ).also { it.acquire(12 * 60 * 60 * 1000L /* 12 hours */) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.inputbridge.bridge.ACTION_STOP"
    }
}
