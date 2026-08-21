package com.inputbridge.bridge.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.inputbridge.bridge.R
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.config.TransportMode
import com.inputbridge.core.discovery.AutoDiscovery
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import com.inputbridge.input.UsbInputCapture
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.protocol.PacketSerializer
import com.inputbridge.protocol.PacketType
import com.inputbridge.transport.bt.BluetoothHidTransport
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BridgeService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "bridge_service"
private const val COUNTER_FLUSH_INTERVAL_MS = 1_000L
private const val PING_INTERVAL_MS = 1_000L
private const val PONG_TIMEOUT_MS = 10_000L   // no PONG for this long → reconnect
private const val WATCHDOG_CHECK_MS = 3_000L
private const val WATCHDOG_GRACE_MS = 15_000L  // wait before first watchdog check
private const val PAIR_TIMEOUT_MS = 10_000L    // wait this long for PAIR_RESPONSE
private const val USB_POLL_INTERVAL_MS = 3_000L // BUG-099: poll for USB devices every 3s

/**
 * Foreground service that owns the USB input capture pipeline and the active transport.
 *
 * Transport modes:
 *   UDP (default): UdpTransport + pairing handshake + PING/PONG + auto-reconnect.
 *   BT HID (Phase 6): BluetoothHidTransport — raw HID reports sent directly to host;
 *     no receiver app required on the host; no pairing or PING/PONG needed.
 *
 * Lifecycle:
 * 1. startForegroundService() → onCreate → onStartCommand → startPipeline()
 *    - Dispatches to startUdpPipeline() or startBluetoothHidPipeline() based on prefs
 * 2. USB device attached → request permission → startCapture()
 *    - UsbInputCapture emits InputEvents on IO thread
 *    - Events dispatched to UDP or BT HID transport depending on active mode
 * 3. (UDP only) Watchdog detects PONG timeout → triggerReconnect() → exponential backoff
 * 4. ACTION_STOP intent → stopSelf() → onDestroy → full cleanup
 *
 * Idempotency: startPipeline() is guarded by [pipelineStarted] so repeated
 * onStartCommand calls (e.g. BootReceiver firing while already running) are no-ops.
 */
class BridgeService : Service() {

    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            handleRuntimeFailure("bridge background task", throwable)
        }
    }
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + serviceExceptionHandler
    )
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private var usbManager: UsbManager? = null
    // BUG-075 FIX: use Koin singleton instead of creating a fresh instance with the Service context.
    private val prefs: BridgePreferences by inject()

    private val packetFactory = EventPacketFactory()

    // ── Transport instances (only one active at a time) ───────────────────────
    private var udpTransport: UdpTransport? = null
    private var btTransport: BluetoothHidTransport? = null

    // ── Jobs ──────────────────────────────────────────────────────────────────
    private var usbCapture: UsbInputCapture? = null
    private var captureJob: Job? = null
    private var counterFlushJob: Job? = null
    private var pingJob: Job? = null
    private var pongResponseJob: Job? = null
    private var watchdogJob: Job? = null
    private var usbPollJob: Job? = null
    private var autoDiscoveryJob: Job? = null

    /**
     * BUG-099 FIX: track the last known USB device so we don't re-request permission
     * on every poll cycle.
     */
    @Volatile private var lastKnownUsbDevice: UsbDevice? = null

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

    private val discoveryConnecting = AtomicBoolean(false)

    /**
     * Hot-path latency trace: time from InputEvent emission to transport send() return
     * in microseconds. Written by the captureJob on IO thread; flushed to DiagnosticsData
     * every second by counterFlushJob.
     */
    private val lastCaptureToSendUs = AtomicLong(0L)

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
        // BUG-157 FIX: a device without the USB-host feature (the merged APK installs
        // everywhere because usb.host is required="false") must not crash in onCreate,
        // which would skip startForeground and get the process killed. Degrade to a
        // network/Bluetooth-only bridge instead.
        usbManager = getSystemService(USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            BridgeLogger.w(TAG, "USB service unavailable on this device — USB capture disabled")
        }
        createNotificationChannel()
        // BUG-063 FIX: Android 14 (API 34) throws MissingForegroundServiceTypeException when
        // the manifest declares android:foregroundServiceType but startForeground() omits the
        // type. Pass FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE on API 29+ (when the 3-arg
        // overload was introduced); fall back to the 2-arg form only on API < 29.
        // BUG-157 FIX: on API 33+ a denied POST_NOTIFICATIONS makes startForeground throw
        // RemoteServiceException and the system kills the process. We MUST still call it
        // (the 5s startForeground deadline applies regardless), but catch the failure so a
        // missing notification permission degrades to "running without a notification" instead
        // of a crash.
        val notification = buildNotification("Starting…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "startForeground failed (notification permission denied?): ${e.message}")
        }
        acquireWakeLock()
        acquireWifiLock()
        registerUsbReceiver()
        // BUG-099 FIX: scan for USB HID devices immediately at service creation.
        // Many combo receivers (Portronics Key2 Combo) report device class=0 and
        // the ATTACHED broadcast may not fire if the device was plugged in before
        // the manifest filter matched. Running this early ensures USB detection
        // is independent of network pairing, IP config, and transport startup.
        checkPreAttachedUsb()
        DiagnosticsManager.update { copy(bridgeServiceRunning = true) }
        BridgeLogger.i(TAG, "BridgeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BridgeLogger.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }
        // BUG-128 FIX: re-run pairing with the current PIN without restarting the pipeline.
        // Lets a PIN/target-IP change made in Settings after the service started take effect
        // (otherwise the stale PIN sent at pipeline start keeps getting rejected).
        if (intent?.action == ACTION_REPAIR) {
            val transport = udpTransport
            if (transport != null) {
                BridgeLogger.i(TAG, "Re-pair action received — re-running handshake")
                serviceScope.launch { rePair(transport) }
            } else {
                // BUG-134 FIX: the manual-IP fallback was dead here. On a fresh install
                // the pipeline never started (blank targetIp), so ACTION_REPAIR found a
                // null transport, returned early, and the user's typed IP never connected.
                // When an IP is configured but no transport exists yet, (re)start the
                // pipeline so the configured IP actually takes effect.
                val ip = prefs.targetIp
                if (ip.isNotBlank()) {
                    BridgeLogger.i(TAG, "Re-pair with no live transport — (re)starting pipeline to $ip")
                    pipelineStarted.set(false)
                    serviceScope.launch {
                        try {
                            startPipeline()
                        } catch (t: Throwable) {
                            if (t !is CancellationException) handleRuntimeFailure("bridge re-pair", t)
                        }
                    }
                } else {
                    BridgeLogger.d(TAG, "Re-pair ignored — no target IP configured yet")
                }
            }
            return START_STICKY
        }
        if (!pipelineStarted.compareAndSet(false, true)) {
            BridgeLogger.d(TAG, "onStartCommand: pipeline already starting/running — ignoring")
            return START_STICKY
        }
        serviceScope.launch {
            try {
                startPipeline()
            } catch (t: Throwable) {
                if (t !is CancellationException) handleRuntimeFailure("bridge startup", t)
            }
        }
        return START_STICKY
    }

    private fun handleRuntimeFailure(stage: String, throwable: Throwable) {
        BridgeLogger.e(TAG, "$stage failed — stopping service", throwable)
        val detail = "${throwable.javaClass.simpleName}: " +
            (throwable.message?.take(180) ?: "no message")
        DiagnosticsManager.update {
            copy(
                bridgeServiceRunning = false,
                transportConnected = false,
                inputCaptureActive = false,
                lastError = "$stage: $detail",
            )
        }
        runCatching { updateNotification("Service error — open Diagnostics") }
        runCatching { stopSelf() }
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

        // 2. Capture transport references before nulling fields, then clean up
        // BUG-XXX FIX: the old code launched a coroutine that read these fields AFTER
        // nulling them on the calling thread, so the coroutine always saw null.
        val capturedUsb = usbCapture
        val capturedUdp = udpTransport
        val capturedBt  = btTransport
        usbCapture    = null
        udpTransport  = null
        btTransport   = null

        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            runCatching { capturedUsb?.stop() }
            runCatching {
                capturedUdp?.send(packetFactory.makeDisconnect())
                delay(60L)
            }
            runCatching { capturedUdp?.disconnect() }
            runCatching { capturedBt?.disconnect() }
        }

        // 3. Cancel scope
        serviceScope.cancel()
        usbPollJob?.cancel()
        pipelineStarted.set(false)
        reconnectInProgress.set(false)
        releaseWifiLock()
        releaseWakeLock()
        DiagnosticsManager.update {
            copy(
                bridgeServiceRunning = false,
                transportConnected = false,
                inputCaptureActive = false,
                usbDeviceConnected = false,
                isReconnecting = false,
                btConnected = false,
                btDeviceName = "",
            )
        }
        BridgeLogger.i(TAG, "BridgeService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Pipeline dispatcher ───────────────────────────────────────────────────

    /**
     * Dispatch to the correct pipeline based on the user's saved transport mode.
     *
     * BUG-059 FIX: explicit arms for all TransportMode values — no else. Compiler
     * now enforces exhaustiveness so adding a new TransportMode without a handler
     * becomes a compile error rather than a silent UDP fallback (§4.2 invariant).
     */
    private suspend fun startPipeline() {
        when (prefs.transportMode) {
            TransportMode.BLUETOOTH_HID -> startBluetoothHidPipeline()
            TransportMode.UDP           -> startUdpPipeline()
            // WIFI_DIRECT and TCP are Phase-8 stubs (FeatureFlags.WIFI_DIRECT_ENABLED = false).
            // Fall back to UDP explicitly with a visible warning rather than silently.
            TransportMode.WIFI_DIRECT,
            TransportMode.TCP           -> {
                BridgeLogger.w(TAG, "${prefs.transportMode} not yet implemented — falling back to UDP")
                DiagnosticsManager.update {
                    copy(lastError = "${prefs.transportMode} not yet implemented; using UDP")
                }
                startUdpPipeline()
            }
        }
    }

    // ── UDP pipeline ──────────────────────────────────────────────────────────

    private suspend fun startUdpPipeline() {
        val port = prefs.port
        // BUG-130 FIX: auto-discovery runs unconditionally so the receiver IP is found
        // automatically on the same Wi-Fi/hotspot — no manual IP entry required.
        startAutoDiscovery()

        val targetIp = prefs.targetIp
        if (targetIp.isBlank()) {
            BridgeLogger.i(TAG, "No target IP configured — auto-discovery searching for receiver…")
            updateNotification("Searching for receiver on network…")
            DiagnosticsManager.update {
                copy(transportMode = "UDP", transportConnected = false,
                     lastError = "No IP configured — listening for receiver broadcast")
            }
            return
        }
        connectToReceiver(targetIp, port)
    }

    /**
     * BUG-130 FIX: listen for receiver presence broadcasts for the lifetime of the
     * pipeline. On discovery (or re-discovery of a different peer) (re)connect.
     */
    private fun startAutoDiscovery() {
        autoDiscoveryJob?.cancel()
        autoDiscoveryJob = serviceScope.launch {
            // BUG-133 FIX: listen for the receiver's announcement AND actively query so a
            // dropped broadcast in either direction still results in a connection.
            launch { AutoDiscovery.startQuerying() }
            AutoDiscovery.listenForReceiver { ip, port ->
                BridgeLogger.i(TAG, "Auto-discovered receiver: $ip:$port")
                // BUG-155 FIX: discovery proves an IP exists — the stale red
                // "No IP configured" from startup would otherwise persist forever.
                DiagnosticsManager.update { copy(targetIp = ip, lastError = null) }
                if (prefs.targetIp == ip && udpTransport?.isConnected == true) return@listenForReceiver
                prefs.targetIp = ip
                prefs.port = port
                updateNotification("Found receiver at $ip:$port — connecting…")
                // BUG-180: don't fight an in-progress reconnect loop
                if (reconnectInProgress.get()) return@listenForReceiver
                // BUG-158: guard so repeated receiver broadcasts don't spawn parallel connects/leak sockets
                if (!discoveryConnecting.compareAndSet(false, true)) return@listenForReceiver
                serviceScope.launch {
                    try {
                        pingJob?.cancel();         pingJob = null
                        pongResponseJob?.cancel(); pongResponseJob = null
                        watchdogJob?.cancel();     watchdogJob = null
                        runCatching { udpTransport?.disconnect() }
                        udpTransport = null
                        lastPingSentAtMs = 0L
                        lastPongReceivedMs = 0L
                        pipelineStarted.set(false)
                        connectToReceiver(ip, port)
                    } finally {
                        discoveryConnecting.set(false)
                    }
                }
            }
        }
    }

    /**
     * Connect the UDP sender transport to the receiver and start the hot paths.
     * BUG-131 FIX: pairing is no longer required to function — the bridge connects
     * directly and sends input as soon as the receiver is reachable. The PIN
     * handshake, if a PIN is configured, is best-effort and non-fatal.
     */
    private suspend fun connectToReceiver(targetIp: String, port: Int) {
        val config = TransportConfig(targetIp = targetIp, port = port)
        val transport = UdpTransport(config, isSender = true)
        udpTransport = transport

        if (!transport.connect()) {
            BridgeLogger.w(TAG, "UDP connect failed ($targetIp:$port)")
            updateNotification("Transport error — check Settings / network")
            DiagnosticsManager.update { copy(lastError = "UDP connect failed") }
            pipelineStarted.set(false)
            return
        }

        BridgeLogger.i(TAG, "UDP transport ready → $targetIp:$port")
        // BUG-090 FIX: a UDP socket opening proves only local availability.
        // BUG-155 FIX: it does prove an IP is now configured — drop the stale
        // "No IP configured" startup error.
        DiagnosticsManager.update {
            copy(transportMode = "UDP", transportConnected = false, targetIp = targetIp, lastError = null)
        }

        // Register incoming-packet collector BEFORE sending any packet.
        pairResponseDeferred = CompletableDeferred()
        startIncomingLoop(transport)

        // BUG-095 fix: USB discovery is independent from network pairing.
        checkPreAttachedUsb()

        // BUG-131 FIX: best-effort pairing only — never block input on it.
        if (prefs.pairingPin.isNotEmpty()) {
            doPairing(transport)
        }
        prefs.isPaired = true
        DiagnosticsManager.update { copy(isPaired = true, pairedPeerIp = targetIp) }
        updateNotification("Ready — waiting for USB device / trackpad…")

        counterFlushJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                DiagnosticsManager.flushCounters()
                val captureUs = lastCaptureToSendUs.get()
                if (captureUs > 0L) {
                    DiagnosticsManager.update { copy(captureToSendUs = captureUs) }
                }
            }
        }

        startPingLoop(transport)
        startWatchdog()
        // BUG-099 FIX: start USB polling so devices plugged in after pipeline
        // startup are detected even if the ATTACHED broadcast was missed.
        startUsbPolling()
    }

    // ── Bluetooth HID pipeline ────────────────────────────────────────────────

    /**
     * Registers the phone as a Bluetooth HID keyboard+mouse device.
     * No pairing PIN, no PING/PONG — the BT stack handles connectivity.
     * Any Bluetooth host (tablet, PC, etc.) that pairs with the phone receives
     * a real system-level cursor and keyboard without needing the receiver app.
     */
    private suspend fun startBluetoothHidPipeline() {
        BridgeLogger.i(TAG, "Starting BT HID pipeline")
        updateNotification("Connecting via Bluetooth HID…")

        val bt = BluetoothHidTransport(this)
        bt.targetDeviceAddress = prefs.btTargetDeviceAddress
        // Do NOT assign btTransport yet — only set it after connect() succeeds so that
        // startCapture()'s dispatch guard (btTransport?.isConnected) stays false on failure.

        DiagnosticsManager.update { copy(transportMode = "BT HID") }

        if (!bt.connect()) {
            BridgeLogger.w(TAG, "BT HID connect failed")
            runCatching { bt.disconnect() }  // release any partial BT profile resources
            // btTransport remains null — startCapture() will not route to BT HID
            updateNotification("BT HID failed — enable Bluetooth and pair host device")
            DiagnosticsManager.update {
                copy(
                    lastError = "BT HID connect failed — check Bluetooth is on and host is already paired",
                )
            }
            pipelineStarted.set(false)
            return
        }

        // Assigned only after successful connect — non-null guarantees a live BT session.
        btTransport = bt
        BridgeLogger.i(TAG, "BT HID transport ready")
        DiagnosticsManager.update { copy(transportConnected = true) }
        updateNotification("BT HID ready — waiting for USB device…")

        counterFlushJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                DiagnosticsManager.flushCounters()
                val captureUs = lastCaptureToSendUs.get()
                if (captureUs > 0L) {
                    DiagnosticsManager.update { copy(captureToSendUs = captureUs) }
                }
            }
        }

        checkPreAttachedUsb()
        // BUG-099 FIX: start USB polling for BT HID mode too
        startUsbPolling()
    }

    // ── Incoming-packet loop (UDP only) ───────────────────────────────────────

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
                // BUG-061 FIX: exhaustive when — no else. Compiler now enforces that every
                // new PacketType must be explicitly handled here (§4.2 invariant).
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
                            val latency = android.os.SystemClock.elapsedRealtime() - sentAt
                            if (latency in 0L..10_000L) {
                                lastPongReceivedMs = android.os.SystemClock.elapsedRealtime()
                                DiagnosticsManager.recordLatency(latency)
                                // BUG-090 FIX: a PONG proves the configured receiver is
                                // reachable, unlike merely creating a local UDP socket.
                                // BUG-155 FIX: also clear the stale "No IP configured" error.
                                DiagnosticsManager.update { copy(transportConnected = true, lastError = null) }
                                BridgeLogger.d(TAG, "PONG received — latency=${latency}ms")
                            }
                        }
                    }
                    // BUG-118 FIX (audit H): a DISCONNECT from the receiver (e.g. its PIN was
                    // reset) must clear our own pairing state instead of being ignored as
                    // "unexpected". Otherwise prefs.isPaired stays true and the stale pairing
                    // outlives the receiver's session.
                    PacketType.DISCONNECT -> {
                        BridgeLogger.i(TAG, "DISCONNECT received from receiver — clearing pairing")
                        prefs.isPaired = false
                        DiagnosticsManager.update {
                            copy(
                                isPaired = false,
                                pairedPeerIp = "",
                                transportConnected = false,
                                lastError = "Receiver ended the session (PIN reset/unpair)",
                            )
                        }
                        updateNotification("Receiver disconnected — re-pair to resume")
                    }
                    // Control packets the bridge does not expect to receive from the receiver.
                    PacketType.PING,
                    PacketType.KEEP_ALIVE,
                    PacketType.PAIR_REQUEST,
                    PacketType.PAIR_CONFIRM,
                    PacketType.MODE_SWITCH,
                    PacketType.RECONNECT,
                    PacketType.ACK,
                    PacketType.ERROR -> {
                        BridgeLogger.d(TAG, "Unexpected packet from receiver: ${packet.type}")
                    }
                    // Input event packets: reverse trackpad mode.
                    // The receiver tablet captures touch and sends these back to the bridge
                    // for local injection via the accessibility service.
                    PacketType.KEY_DOWN,
                    PacketType.KEY_UP,
                    PacketType.MOUSE_MOVE,
                    PacketType.MOUSE_DOWN,
                    PacketType.MOUSE_UP,
                    PacketType.SCROLL,
                    PacketType.TEXT_INPUT,
                    PacketType.MODIFIER_STATE,
                    PacketType.SPECIAL_ACTION,
                    PacketType.CURSOR_GOTO -> {
                        BridgeInputInjector.handlePacket(packet)
                    }
                }
            }
        }
    }

    // ── Pairing (UDP only) ────────────────────────────────────────────────────

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

        // BUG-096 fix: preserve null so a missing UDP reply is not presented as a bad PIN.
        val accepted: Boolean? = withTimeoutOrNull(PAIR_TIMEOUT_MS) {
            pairResponseDeferred.await()
        }

        return when (accepted) {
            true -> {
                prefs.isPaired = true
                transport.send(packetFactory.makePairConfirm())
                DiagnosticsManager.update {
                    copy(isPaired = true, pairedPeerIp = prefs.targetIp, transportConnected = true)
                }
                BridgeLogger.i(TAG, "Pairing confirmed")
                updateNotification("Paired — waiting for USB device…")
                // BUG-155 FIX: accepted pairing proves the link is live — clear stale errors.
                DiagnosticsManager.update {
                    copy(isPaired = true, pairedPeerIp = prefs.targetIp, transportConnected = true, lastError = null)
                }
                true
            }
            false -> {
                BridgeLogger.w(TAG, "Pairing rejected by receiver")
                updateNotification("Pairing rejected — check PIN in Settings")
                DiagnosticsManager.update {
                    copy(isPaired = false, lastError = "Pairing rejected — PIN does not match receiver display")
                }
                false
            }
            null -> {
                BridgeLogger.w(TAG, "Pairing response timed out")
                updateNotification("Pairing timed out — check receiver is listening")
                DiagnosticsManager.update {
                    copy(isPaired = false, lastError = "Pairing timed out — check receiver, IP, port, and Wi-Fi")
                }
                false
            }
        }
    }

    /**
     * BUG-128 FIX: re-run the pairing handshake against the current PIN without
     * tearing down the pipeline. Used when the user changes the PIN or target IP in
     * Settings while the service is already running. Resets [pairResponseDeferred] so
     * the fresh PAIR_REQUEST awaits a fresh PAIR_RESPONSE.
     */
    private suspend fun rePair(transport: UdpTransport) {
        if (prefs.pairingPin.isEmpty()) {
            BridgeLogger.i(TAG, "Re-pair: no PIN configured — clearing pairing state")
            prefs.isPaired = false
            DiagnosticsManager.update { copy(isPaired = false, pairedPeerIp = "") }
            return
        }
        pairResponseDeferred = CompletableDeferred()
        val paired = doPairing(transport)
        if (!paired) {
            BridgeLogger.w(TAG, "Re-pair failed — PIN still rejected or timed out")
        }
    }

    // ── PING keep-alive (UDP only) ────────────────────────────────────────────

    private fun startPingLoop(transport: UdpTransport) {
        pingJob = serviceScope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                val ping = packetFactory.makePing()
                lastPingSentAtMs = android.os.SystemClock.elapsedRealtime()
                DiagnosticsManager.update { copy(lastPingSentMs = lastPingSentAtMs) }
                transport.send(ping)
                BridgeLogger.d(TAG, "PING sent (seq=${ping.sequenceNo})")
            }
        }
    }

    // ── Watchdog + reconnect (UDP only) ──────────────────────────────────────

    /**
     * Periodically checks whether PONGs are still arriving.
     * If no PONG for [PONG_TIMEOUT_MS] after the grace period, triggers reconnect.
     */
    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            delay(WATCHDOG_GRACE_MS)  // wait for initial connection to settle
            while (isActive) {
                delay(WATCHDOG_CHECK_MS)
                val now = android.os.SystemClock.elapsedRealtime()
                val lastPong = lastPongReceivedMs
                val lastPing = lastPingSentAtMs
                val pongStale = lastPong > 0L && (now - lastPong) > PONG_TIMEOUT_MS
                                // BUG-159: also reconnect if no PONG ever arrived (dead peer before first PONG)
val neverPonged = lastPong == 0L && lastPing > 0L && (now - lastPing) > PONG_TIMEOUT_MS
                if (pongStale || neverPonged) {
                    BridgeLogger.w(TAG, "Watchdog: no PONG for ${now - lastPong}ms — triggering reconnect")
                    launch { triggerReconnect() }
                    break
                }
            }
        }
    }

    /**
     * Exponential-backoff reconnect loop (UDP only).
     * Attempts: 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, … (up to 10 attempts).
     * Guarded by [reconnectInProgress] so only one loop runs at a time.
     */
    private suspend fun triggerReconnect() {
        if (!reconnectInProgress.compareAndSet(false, true)) return
        // BUG-180: a discovery-driven connect owns the link right now — don't race it
        if (discoveryConnecting.get()) {
            reconnectInProgress.set(false)
            return
        }

        // Cancel currently running I/O jobs (but keep serviceScope alive)
        pingJob?.cancel();         pingJob = null
        pongResponseJob?.cancel(); pongResponseJob = null
        watchdogJob?.cancel();     watchdogJob = null
        runCatching { udpTransport?.disconnect() }
        udpTransport = null
        lastPingSentAtMs = 0L
        lastPongReceivedMs = 0L
        lastCaptureToSendUs.set(0L)  // BUG-049 fix: reset stale latency so Diagnostics shows 0 not prior-session value

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
            // BUG-180: abort the backoff loop if discovery took over the connection
            if (discoveryConnecting.get()) {
                DiagnosticsManager.update { copy(isReconnecting = false) }
                reconnectInProgress.set(false)
                return
            }

            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)

            if (transport.connect()) {
                udpTransport = transport
                // BUG-090 FIX: creating a local socket proves nothing about the peer —
                // the PONG handler (startIncomingLoop) flips transportConnected once the
                // receiver is actually reachable. Report the reconnection state only.
                DiagnosticsManager.update { copy(isReconnecting = false) }
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
                        // BUG-104: null out udpTransport so the service knows it's dead.
                        // Without this, udpTransport points to a disconnected socket and
                        // no further reconnect attempts are made.
                        udpTransport = null
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

    /** Check for HID devices already connected when the service starts. */
    private fun checkPreAttachedUsb() {
        val mgr = usbManager ?: return
        val allDevices = mgr.deviceList
        BridgeLogger.i(TAG, "checkPreAttachedUsb: UsbManager.deviceList has ${allDevices.size} device(s)")
        for ((name, dev) in allDevices) {
            val ifaceClasses = (0 until dev.interfaceCount).map { i ->
                val iface = dev.getInterface(i)
                "cls=${iface.interfaceClass}/sub=${iface.interfaceSubclass}/proto=${iface.interfaceProtocol}"
            }
            BridgeLogger.d(TAG, "  USB device: $name (class=${dev.deviceClass}, " +
                "vendor=${dev.vendorId}, product=${dev.productId}, " +
                "interfaces=[${ifaceClasses.joinToString()}])")
        }

        val preAttached = allDevices.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID
            }
        }
        if (preAttached != null) {
            BridgeLogger.i(TAG, "Pre-attached HID device found: ${preAttached.deviceName} " +
                "(vendor=${preAttached.vendorId}, product=${preAttached.productId})")
            lastKnownUsbDevice = preAttached
            onUsbAttached(preAttached)
        } else {
            BridgeLogger.w(TAG, "No HID device found in UsbManager.deviceList " +
                "(${allDevices.size} device(s) scanned — device may be blacklisted by framework)")
        }
    }

    /**
     * BUG-099 FIX: poll UsbManager.deviceList every 3 seconds as a fallback.
     * Handles cases where:
     * - USB_DEVICE_ATTACHED broadcast was never delivered (device class=0, filter miss)
     * - Device was plugged in after the service started but before polling began
     * - OEM ROM silently swallows the broadcast
     *
     * Only triggers on new devices not already tracked by [lastKnownUsbDevice].
     */
    private fun startUsbPolling() {
        val mgr = usbManager ?: return
        usbPollJob = serviceScope.launch {
            var pollCount = 0L
            while (isActive) {
                delay(USB_POLL_INTERVAL_MS)
                pollCount++
                if (usbCapture?.isActive == true) continue  // already capturing — skip
                val device = mgr.deviceList.values.firstOrNull { dev ->
                    (0 until dev.interfaceCount).any { i ->
                        dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID
                    }
                }
                when {
                    device == null -> {
                        if (pollCount % 10L == 0L) {
                            // Log periodically when no device is found (helps diagnose blacklist issue)
                            BridgeLogger.d(TAG, "USB poll #$pollCount: no HID device in deviceList " +
                                "(${mgr.deviceList.size} total)")
                        }
                    }
                    // A known device that STILL lacks permission: do NOT re-request here —
                    // re-requesting every 3 s spams the permission dialog. The foreground
                    // activity's requester handles this case (§5.6 / BUG-129).
                    (device == lastKnownUsbDevice && !mgr.hasPermission(device)) -> Unit
                    // BUG-143 FIX: a known device that NOW has permission but isn.t capturing.
                    // This is the permission-grant deadlock: the service was started (START
                    // button / boot) before the user granted USB permission in the activity,
                    // and onStartCommand's idempotency guard swallowed the later grant. Re-enter
                    // capture here now that the grant finally landed.
                    (device == lastKnownUsbDevice && mgr.hasPermission(device)
                            && usbCapture?.isActive != true) -> {
                        BridgeLogger.i(TAG, "USB poll #$pollCount: permission granted for " +
                            "${device.deviceName} — (re)starting capture")
                        serviceScope.launch { startCapture(device) }
                    }
                    // A genuinely new device: track it and run the normal attach path.
                    else -> {
                        BridgeLogger.i(TAG, "USB poll #$pollCount found NEW HID device: " +
                            "${device.deviceName} (vendor=${device.vendorId}, product=${device.productId})")
                        lastKnownUsbDevice = device
                        onUsbAttached(device)
                    }
                }
            }
        }
    }

    private fun onUsbAttached(device: UsbDevice) {
        val mgr = usbManager ?: return
        BridgeLogger.i(TAG, "USB HID device attached: ${device.deviceName}")
        // BUG-094 fix: hardware detection is useful before permission/capture succeeds.
        // Keep it separate from inputCaptureActive so the UI never claims no device exists.
        DiagnosticsManager.update {
            copy(
                usbDeviceConnected = true,
                usbDeviceName = device.deviceName ?: "Unknown",
                usbPermissionGranted = mgr.hasPermission(device),
                inputCaptureActive = false,
            )
        }

        if (mgr.hasPermission(device)) {
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
        // FLAG_MUTABLE is REQUIRED here. The Android USB system needs to write
        // EXTRA_PERMISSION_GRANTED and EXTRA_DEVICE into this PendingIntent before
        // delivering it. FLAG_IMMUTABLE would silently block those writes, causing
        // the receiver to always see granted=false even when the user tapped Allow.
        // See: https://developer.android.com/guide/topics/connectivity/usb/host#permission-d
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager?.requestPermission(device, pi)
        BridgeLogger.i(TAG, "USB permission requested for ${device.deviceName}")
    }

    private suspend fun startCapture(device: UsbDevice) {
        stopCapture()
        BridgeLogger.i(TAG, "startCapture: initializing for ${device.deviceName} " +
            "(interfaces=${device.interfaceCount}, class=${device.deviceClass})")

        val capture = UsbInputCapture(this, device)
        usbCapture = capture

        // BUG-086 FIX: InputCapture.events has replay=0. Subscribe before start()
        // launches USB readers so the first keyboard or mouse report cannot race
        // past an absent collector.
        captureJob = serviceScope.launch {
            var eventCount = 0L
            // BUG-139: bridge-side sensitivity is fixed for the lifetime of a capture
            // session. Cache it once so the 125 Hz mouse stream never re-reads the
            // SharedPreferences-backed property on the hot path.
            val sensitivity = prefs.bridgeSensitivity
            capture.events.collect { rawEvent ->
                val t0 = System.nanoTime()
                eventCount++

                // BUG-095 fix: capture can start before pairing, but a configured PIN must
                // still prevent keyboard/mouse events from leaving the bridge until accepted.
                if (prefs.pairingPin.isNotEmpty() && !prefs.isPaired) {
                    if (eventCount <= 5L || eventCount % 100L == 0L) {
                        BridgeLogger.d(TAG, "Event #$eventCount dropped — waiting for pairing")
                    }
                    return@collect
                }

                // Apply bridge-side sensitivity to mouse movement deltas.
                // prefs.bridgeSensitivity is 0.1–5.0; default 1.0 (no change).
                val event: com.inputbridge.core.model.InputEvent = run {
                    if (sensitivity != 1.0f && rawEvent is com.inputbridge.core.model.InputEvent.MouseMove) {
                        rawEvent.copy(dx = rawEvent.dx * sensitivity, dy = rawEvent.dy * sensitivity)
                    } else rawEvent
                }

                if (eventCount <= 3L || eventCount % 500L == 0L) {
                    BridgeLogger.i(TAG, "Event #$eventCount: ${rawEvent::class.simpleName} " +
                        "→ transport=${if (btTransport?.isConnected == true) "BT" else "UDP"}")
                }

                // Guard: btTransport is only non-null when connect() succeeded.
                // isConnected is checked in addition for defense-in-depth against
                // a BT host that disconnects while capture is already running.
                val bt = btTransport?.takeIf { it.isConnected }
                if (bt != null) {
                    val sent = bt.sendInputEvent(event)
                    if (sent) DiagnosticsManager.onPacketSent()
                    else {
                        DiagnosticsManager.onSendFailed()
                        BridgeLogger.w(TAG, "BT send failed for event #$eventCount")
                    }
                    lastCaptureToSendUs.set((System.nanoTime() - t0) / 1_000L)
                } else {
                    val packet = packetFactory.fromEvent(event) ?: return@collect
                    // BUG-139: high-frequency mouse/scroll deltas skip the UDP inputChannel
                    // + select() dispatch hop by sending inline on this collector thread via
                    // sendDirect(). This is the same fast path the trackpad already uses, and it
                    // removes one coroutine context switch per packet from the 125 Hz stream.
                    // Keys/clicks stay on the channel so they keep their ordering guarantees.
                    val sent = if (event is com.inputbridge.core.model.InputEvent.MouseMove ||
                        event is com.inputbridge.core.model.InputEvent.Scroll
                    ) {
                        try {
                            udpTransport?.sendDirect(packet) ?: false
                        } catch (e: Exception) {
                            BridgeLogger.w(TAG, "UDP sendDirect failed", e)
                            false
                        }
                    } else {
                        try {
                            udpTransport?.send(packet) ?: false
                        } catch (e: Exception) {
                            BridgeLogger.w(TAG, "UDP send failed", e)
                            false
                        }
                    }
                    if (sent) {
                        DiagnosticsManager.onPacketSent()
                        lastCaptureToSendUs.set((System.nanoTime() - t0) / 1_000L)
                    } else {
                        DiagnosticsManager.onSendFailed()
                        if (eventCount <= 5L || eventCount % 200L == 0L) {
                            BridgeLogger.w(TAG, "UDP send failed for event #$eventCount " +
                                "(udpTransport=${udpTransport != null})")
                        }
                    }
                }
            }
        }

        BridgeLogger.i(TAG, "Calling UsbInputCapture.start()…")
        if (!capture.start()) {
            captureJob?.cancel()
            captureJob = null
            usbCapture = null
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
        // BUG-100 FIX: RECEIVER_NOT_EXPORTED blocks system broadcasts (ACTION_USB_DEVICE_ATTACHED,
        // ACTION_USB_DEVICE_DETACHED) on Android 13+. These are sent by the system and must
        // reach our receiver. Use RECEIVER_EXPORTED so system intents are delivered.
        // ACTION_USB_PERMISSION is app-specific but the system sends it back via the
        // PendingIntent, so it also requires RECEIVER_EXPORTED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
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
        // BUG-142 FIX (single-APK merge): the old launcher MainActivity was removed from the
        // manifest when app-bridge became a library. The bridge's persistent
        // notification must open the merged app's bridge activity instead, resolved
        // by name (the library cannot compile against the :app module).
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent().setClassName(this, "com.inputbridge.ui.bridge.BridgeModeActivity"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("InputBridge Active")
            .setContentText(status)
            // BUG-076 FIX: system drawables may be absent on OEM ROMs; use app-owned resource.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Bridge Service", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "Keeps the USB input bridge alive"
                setShowBadge(false)
            }
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(ch)
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InputBridge::BridgeWakeLock",
        ).also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun acquireWifiLock() {
        runCatching {
            val wifiManager = getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            wifiLock = wifiManager.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "InputBridge::LowLatency",
            ).also { it.acquire() }
            BridgeLogger.i(TAG, "WifiLock acquired (WIFI_MODE_FULL_LOW_LATENCY)")
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "com.inputbridge.bridge.ACTION_STOP"
        const val ACTION_REPAIR = "com.inputbridge.bridge.ACTION_REPAIR"
        private const val ACTION_USB_PERMISSION = "com.inputbridge.bridge.USB_PERMISSION"
    }
}
