package com.inputbridge.receiver.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.receiver.ui.MainActivity
import kotlinx.coroutines.*

private const val TAG = "ReceiverService"
private const val NOTIFICATION_ID = 2001
private const val CHANNEL_ID = "receiver_service"

/**
 * Foreground service keeping the UDP listener and accessibility command bus alive.
 * Receives packets from the bridge and dispatches them to the accessibility service.
 */
class ReceiverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for bridge…"))
        DiagnosticsManager.update { copy(receiverServiceRunning = true) }
        BridgeLogger.i(TAG, "ReceiverService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        serviceScope.launch { startListening() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        DiagnosticsManager.update { copy(receiverServiceRunning = false) }
        BridgeLogger.i(TAG, "ReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startListening() {
        // Phase 3+: wire UdpTransport → PacketSerializer → AccessibilityCommandBus
        BridgeLogger.i(TAG, "Receiver listening (Phase 1 scaffold)")
        updateNotification("Listening on UDP port 54321")
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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

    companion object {
        const val ACTION_STOP = "com.inputbridge.receiver.ACTION_STOP"
    }
}
