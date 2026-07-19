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
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*

private const val TAG = "BridgeService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "bridge_service"
private const val COUNTER_FLUSH_INTERVAL_MS = 1_000L

/**
 * Foreground service that owns the USB input capture and UDP transport pipeline.
 *
 * Lifecycle:
 * 1. startForegroundService() → onCreate → onStartCommand → startPipeline()
 *    - Connects UdpTransport (sender mode)
 *    - Registers dynamic BroadcastReceiver for USB events
 *    - Checks for pre-attached USB devices
 * 2. USB device attached → request permission (if needed) → startCapture()
 *    - UsbInputCapture emits InputEvents on IO thread
 *    - Events flow through EventPacketFactory → UdpTransport.send()
 *    - DiagnosticsManager counters updated per packet
 * 3. USB device detached → stopCapture()
 * 4. ACTION_STOP intent → stopSelf() → onDestroy → full cleanup
 *
 * Idempotency: startPipeline() is guarded by [pipelineStarted] so repeated
 * onStartCommand calls (e.g. BootReceiver firing while already running) are no-ops.
 *
 * Teardown: individual jobs are cancelled first, then resources are cleaned up
 * in a NonCancellable context before serviceScope is cancelled.
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

    /** Guards against duplicate pipeline starts from repeated onStartCommand calls. */
    @Volatile private var pipelineStarted = false

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
        // Guard: ignore repeated starts (e.g. BootReceiver firing on an already-running service)
        if (pipelineStarted) {
            BridgeLogger.d(TAG, "onStartCommand: pipeline already running, ignoring")
            return START_STICKY
        }
        serviceScope.launch { startPipeline() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbReceiver()

        // 1. Cancel tracked jobs to stop new work immediately
        counterFlushJob?.cancel()
        captureJob?.cancel()

        // 2. Clean up resources (USB + socket) in NonCancellable context so teardown
        //    completes even though serviceScope will be cancelled next.
        //    runBlocking is acceptable here: onDestroy is called on the main thread,
        //    these operations are fast (cancel internal jobs + close file/socket).
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { usbCapture?.stop() }
                runCatching { udpTransport?.disconnect() }
            }
        }
        usbCapture = null
        udpTransport = null

        // 3. Cancel the scope after resources are freed
        serviceScope.cancel()
        pipelineStarted = false
        releaseWakeLock()
        DiagnosticsManager.update {
            copy(
                bridgeServiceRunning = false,
                transportConnected = false,
                inputCaptureActive = false,
                usbDeviceConnected = false,
            )
        }
        BridgeLogger.i(TAG, "BridgeService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Pipeline setup ────────────────────────────────────────────────────────

    private suspend fun startPipeline() {
        pipelineStarted = true
        val targetIp = prefs.targetIp
        val port = prefs.port

        if (targetIp.isBlank()) {
            BridgeLogger.w(TAG, "Target IP not configured — set it in Settings")
            updateNotification("Set receiver IP in Settings first")
            DiagnosticsManager.update { copy(lastError = "Target IP not configured") }
        } else {
            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)
            udpTransport = transport
            if (transport.connect()) {
                BridgeLogger.i(TAG, "UDP transport ready → $targetIp:$port")
                DiagnosticsManager.update { copy(transportConnected = true, targetIp = targetIp) }
                updateNotification("Ready — waiting for USB device…")
            } else {
                BridgeLogger.w(TAG, "UDP connect failed ($targetIp:$port)")
                updateNotification("Transport error — check Settings")
                DiagnosticsManager.update { copy(lastError = "UDP connect failed") }
            }
        }

        // Periodic diagnostics counter flush (1 s interval)
        counterFlushJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                DiagnosticsManager.flushCounters()
            }
        }

        // Handle USB devices that were already attached before service started
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
        updateNotification("USB device disconnected")
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
        stopCapture() // cancel any previous capture first

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

        // Hot path: InputEvent → Packet → UDP
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
