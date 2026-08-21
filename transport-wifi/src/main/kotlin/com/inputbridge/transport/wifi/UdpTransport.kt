package com.inputbridge.transport.wifi

import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.protocol.Packet
import com.inputbridge.protocol.PacketSerializer
import com.inputbridge.protocol.PacketType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

private const val TAG = "UdpTransport"
private const val MAX_PACKET_SIZE = 1400  // stay safely under Ethernet MTU

/** High-frequency input events (mouse, key). Packets dropped when full — expected. */
private const val INPUT_QUEUE_CAPACITY = 64

/**
 * Critical control packets: PING, PONG, PAIR_*, DISCONNECT, ERROR.
 * UNLIMITED so they are never silently dropped by a busy mouse pipeline.
 */
private const val CRITICAL_QUEUE_CAPACITY = Channel.UNLIMITED

/** Socket send/receive buffer (64 KB). Small buffers for interactive traffic — large
 *  256 KB buffers cause bufferbloat that adds latency to a real-time mouse stream. */
private const val SOCKET_BUFFER_BYTES = 64 * 1024

/** IP DSCP EF (0x28) — expedited forwarding; falls back silently if the socket ignores it. */
private const val TRAFFIC_CLASS_LOWDELAY = 0x28

/**
 * UDP transport: lowest latency local transport.
 *
 * Fire-and-forget for input events (UDP is acceptable — packet loss on a local
 * network is rare and a missed mouse-move is better than blocking on TCP ACKs).
 * Control packets (PING, PONG, PAIR_*) use the same socket but are retried if no
 * response arrives within the keepAlive window.
 *
 * Architecture:
 * - One coroutine sends from [sendChannel].
 * - One coroutine receives into [_incomingPackets].
 * - Both run on [Dispatchers.IO].
 *
 * Bidirectional in receiver mode:
 * - The receiver binds to a port and records the sender's InetSocketAddress
 *   from every incoming datagram.
 * - When [send] is called in receiver mode (e.g. PONG reply), the packet is
 *   sent back to the last seen sender address. If no packet has arrived yet,
 *   the send is silently dropped.
 */
class UdpTransport(
    private val config: TransportConfig,
    private val isSender: Boolean, // true on bridge, false on receiver
) : Transport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    // BUG-097 fix: connect() starts the socket reader before service startup has finished
    // installing its collector. Retain the first datagram so an initial PAIR_REQUEST or
    // PAIR_RESPONSE cannot disappear in that short window; each transport is session-local.
    private val _incomingPackets = MutableSharedFlow<Packet>(replay = 1, extraBufferCapacity = 128)

    /**
     * BUG-069 FIX — dual-priority send queues.
     *
     * Before this fix, a single bounded channel of 128 slots was shared between
     * high-frequency mouse-move packets and critical control packets (PING, PONG,
     * PAIR_REQUEST, DISCONNECT). Under heavy mouse traffic the queue could fill,
     * causing `trySend()` to silently drop PING/PONG/DISCONNECT — destabilising the
     * watchdog, delaying pairing, and hiding clean shutdowns.
     *
     * Fix: two independent channels.
     *   criticalChannel — UNLIMITED capacity, never drops.
     *   inputChannel    — bounded capacity, drops old mouse moves if full (acceptable).
     *
     * The send loop always drains [criticalChannel] before processing [inputChannel],
     * ensuring control packets are sent immediately even under burst mouse traffic.
     */
    private var criticalChannel = Channel<ByteArray>(CRITICAL_QUEUE_CAPACITY)
    private var inputChannel    = Channel<ByteArray>(INPUT_QUEUE_CAPACITY)

    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()
    override val incomingPackets: Flow<Packet> = _incomingPackets.asSharedFlow()
    @Volatile
    override var isConnected: Boolean = false
        private set

    private var socket: DatagramSocket? = null
    private var sendJob: Job? = null
    private var receiveJob: Job? = null

    /** Cached destination for sender-mode direct sends (BUG-104). Set in connect(). */
    private var fixedTargetAddress: InetSocketAddress? = null

    /**
     * In receiver mode: the address of the most recently seen sender.
     * Used to send PONG and other control replies back to the bridge.
     * Updated atomically on every received datagram.
     */
    @Volatile private var lastSenderAddress: InetSocketAddress? = null

    /**
     * The IP address string of the most recently seen sender.
     * Null until the first packet has been received.
     * Used by ReceiverService for source-address validation after pairing.
     */
    fun getLastSenderIp(): String? = lastSenderAddress?.address?.hostAddress

    // BUG-173 FIX: resetReplayCache() is experimental — opt in for the connect-time
    // cache invalidation only.
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun connect(): Boolean {
        if (isConnected) return true
        _connectionState.value = ConnectionState.Connecting
        return try {
            // BUG-089 FIX: resolve before announcing success. A malformed address must
            // fail connect(), not kill the asynchronous send coroutine after the UI is live.
            val fixedTarget = if (isSender) InetAddress.getByName(config.targetIp) else null
            fixedTargetAddress = if (fixedTarget != null) {
                InetSocketAddress(fixedTarget, config.port)
            } else {
                null
            }
            val sock = if (isSender) {
                DatagramSocket().also { it.soTimeout = 0 }
            } else {
                DatagramSocket(config.port) // receiver binds to port
            }
            // Socket tuning for minimum latency on local Wi-Fi / hotspot.
            // Large kernel buffers absorb bursts without drops; DSCP EF (0x28) hints
            // to the AP and home router that these datagrams are latency-sensitive.
            // Both calls are best-effort — Android may silently ignore trafficClass on
            // some OEM kernels; that is acceptable.
            runCatching { sock.sendBufferSize    = SOCKET_BUFFER_BYTES }
            runCatching { sock.receiveBufferSize = SOCKET_BUFFER_BYTES }
            runCatching { sock.sendBufferSize = SOCKET_BUFFER_BYTES } // BUG-191
            runCatching { sock.trafficClass      = TRAFFIC_CLASS_LOWDELAY }
            socket = sock
            // BUG-087 FIX: disconnect closes its queues. Every new connection gets
            // fresh queues, and the old send loop retains its own references while it
            // is being cancelled so it cannot consume packets from the new session.
            val newCriticalChannel = Channel<ByteArray>(CRITICAL_QUEUE_CAPACITY)
            val newInputChannel = Channel<ByteArray>(INPUT_QUEUE_CAPACITY)
            criticalChannel = newCriticalChannel
            inputChannel = newInputChannel
            // BUG-092 FIX: the reply endpoint belongs to one receiver-mode session.
            // Never send a new session's control traffic to a previous bridge.
            lastSenderAddress = null
            // BUG-173 FIX: drop any datagram retained by the replay=1 cache from a
            // previous session on this instance. Without this, the first collector of a
            // new session immediately receives the old session's last packet — e.g. a
            // stale DISCONNECT (clears fresh pairing state) or a duplicate input event.
            // BUG-097's within-session guarantee is preserved: the cache still retains
            // the first datagram of THIS session until the first subscriber arrives.
            _incomingPackets.resetReplayCache()
            // BUG-082 FIX: both loops use isConnected as their first condition.
            // Publish the live state before launching them so an immediately scheduled
            // coroutine cannot exit permanently before its first receive/send.
            isConnected = true
            startSendLoop(sock, fixedTarget, newCriticalChannel, newInputChannel)
            startReceiveLoop(sock)
            _connectionState.value = ConnectionState.Connected
            BridgeLogger.i(TAG, "UDP transport connected (sender=$isSender port=${config.port})")
            true
        } catch (e: Exception) {
            // BUG-174 FIX: a failure after resource allocation must not leak the bound
            // socket (port 54321 would stay occupied → every retry BindExceptions) nor
            // strand isConnected=true with orphan send/receive loops if the exception
            // landed after the flag was published. Tear everything down before reporting.
            isConnected = false
            runCatching { criticalChannel.close() }
            runCatching { inputChannel.close() }
            runCatching { socket?.close() }
            socket = null
            BridgeLogger.e(TAG, "UDP connect failed", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            false
        }
    }

    override suspend fun disconnect() {
        if (!isConnected) return
        isConnected = false
        // BUG-092 FIX: no peer endpoint is valid after this socket closes.
        lastSenderAddress = null
        // Close both send queues before cancelling the send job so their coroutine
        // terminates cleanly without a channel leak (BUG-045 principle extended to
        // the new dual-channel layout).
        criticalChannel.close()
        inputChannel.close()
        sendJob?.cancel()
        receiveJob?.cancel()
        socket?.close()
        socket = null
        _connectionState.value = ConnectionState.Disconnected
        BridgeLogger.i(TAG, "UDP transport disconnected")
    }

    override suspend fun send(packet: Packet): Boolean {
        if (!isConnected) return false
        val bytes = PacketSerializer.serialize(packet)
        return if (packet.type.isCritical()) {
            // UNLIMITED capacity — always succeeds unless the channel is closed.
            criticalChannel.trySend(bytes).isSuccess
        } else {
            // Bounded — trySend may drop a stale mouse-move packet. Acceptable.
            inputChannel.trySend(bytes).isSuccess
        }
    }

    /**
     * Non-suspend fast path for latency-critical traffic (mouse moves, cursor
     * positioning). [send] never actually suspends — it only serializes and
     * trySends into a channel — but being declared `suspend` forces callers to
     * hop onto a coroutine dispatcher for every packet. This method lets the
     * trackpad hot path enqueue synchronously on the calling thread.
     */
    fun sendNow(packet: Packet): Boolean {
        if (!isConnected) return false
        val bytes = PacketSerializer.serialize(packet)
        return if (packet.type.isCritical()) {
            criticalChannel.trySend(bytes).isSuccess
        } else {
            inputChannel.trySend(bytes).isSuccess
        }
    }

    /**
     * BUG-104 FIX — absolute lowest-latency send used by the trackpad hot path.
     *
     * [sendNow] enqueues into a channel that the send-loop coroutine must then wake
     * up and drain before the DatagramSocket.send() call happens — one coroutine
     * dispatch hop per packet (~0.1–1ms on Dispatchers.IO). For a real-time mouse
     * stream that hop is pure overhead.
     *
     * [sendDirect] skips the channel entirely: it serializes and calls
     * `socket.send()` synchronously on the calling thread (the trackpad touch
     * handler on Main). DatagramSocket.send() is thread-safe and the datagram is
     * handed to the kernel immediately, so concurrent sends from the send-loop and
     * this method cannot corrupt each other. Control packets still go through
     * [send]/[sendNow] to preserve ordering against the watchdog.
     */
    fun sendDirect(packet: Packet): Boolean {
        if (!isConnected) return false
        val sock = socket ?: return false
        val fixedTarget = fixedTargetAddress ?: return false
        return try {
            val bytes = PacketSerializer.serialize(packet)
            sock.send(DatagramPacket(bytes, bytes.size, fixedTarget))
            true
        } catch (e: Exception) {
            if (isConnected) BridgeLogger.w(TAG, "Direct send error", e)
            false
        }
    }

    /**
     * True for packets that must never be silently dropped by a busy mouse pipeline.
     * Control traffic is at most ~1 packet/second, so the UNLIMITED critical channel
     * cannot grow unbounded in practice.
     */
    private fun PacketType.isCritical() = when (this) {
        PacketType.PING, PacketType.PONG,
        PacketType.PAIR_REQUEST, PacketType.PAIR_RESPONSE, PacketType.PAIR_CONFIRM,
        PacketType.DISCONNECT, PacketType.ERROR,
        // BUG-175 FIX: RECONNECT, ACK, MODE_SWITCH, and KEEP_ALIVE are control packets
        // that must not be dropped under mouse traffic. Route them through the unlimited queue.
        PacketType.RECONNECT, PacketType.ACK, PacketType.MODE_SWITCH, PacketType.KEEP_ALIVE -> true
        // BUG-175 FIX: enumerate input-event types explicitly instead of `else -> false`
        // (§4.2 — no else in when over PacketType). The compiler now forces an explicit
        // decision for every newly appended PacketType; a future control type can no
        // longer silently fall into the droppable 64-slot input queue.
        PacketType.KEY_DOWN, PacketType.KEY_UP,
        PacketType.MOUSE_MOVE, PacketType.MOUSE_DOWN, PacketType.MOUSE_UP,
        PacketType.SCROLL, PacketType.TEXT_INPUT, PacketType.MODIFIER_STATE,
        PacketType.SPECIAL_ACTION, PacketType.CURSOR_GOTO -> false
    }

    private fun startSendLoop(
        sock: DatagramSocket,
        fixedTarget: InetAddress?,
        criticalQueue: Channel<ByteArray>,
        inputQueue: Channel<ByteArray>,
    ) {
        sendJob = scope.launch {
            // BUG-104 FIX: boost scheduling priority. DatagramSocket.send() and the
            // channel reads run on Dispatchers.IO; without this the kernel/ART scheduler
            // can delay a mouse-move packet by a few ms behind unrelated IO work.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            /**
             * Priority send: drain criticalChannel first (non-blocking try), then fall
             * back to a coroutine select on both channels. This ensures PING/PONG/PAIR/
             * DISCONNECT are always emitted immediately even under 125 Hz mouse traffic.
             *
             * BUG-074 FIX: wrap the entire loop in try/catch.
             * When disconnect() is called it closes both channels and then cancels this
             * job. If the coroutine is blocked inside select{} at that moment, closing
             * the channels causes ClosedReceiveChannelException to propagate out of the
             * select before the cancellation signal arrives. Without this catch the
             * exception exits the coroutine via the uncaught-exception path — noisy logs
             * and a potential SupervisorJob failure callback. Catching it explicitly gives
             * a clean, silent shutdown consistent with the expected lifecycle.
             */
            try {
                while (isConnected) {
                    val bytes = criticalQueue.tryReceive().getOrNull()
                        ?: select {
                            criticalQueue.onReceive { it }
                            inputQueue.onReceive { it }
                        }

                    // Resolve the destination address — either the fixed bridge target
                    // (sender mode) or the last seen sender address (receiver mode).
                    val destination: InetSocketAddress
                    if (fixedTarget != null) {
                        destination = InetSocketAddress(fixedTarget, config.port)
                    } else {
                        val senderAddr = lastSenderAddress
                        if (senderAddr == null) {
                            BridgeLogger.d(TAG, "Receiver send skipped — no sender address seen yet")
                            continue
                        }
                        // BUG-081 FIX: senderAddr includes the bridge's ephemeral
                        // source port. Pairing/PONG replies must go back to that exact
                        // endpoint, not to the receiver's configured listen port.
                        destination = senderAddr
                    }

                    try {
                        val dp = DatagramPacket(bytes, bytes.size, destination)
                        sock.send(dp)
                    } catch (e: Exception) {
                        if (isConnected) BridgeLogger.w(TAG, "Send error", e)
                    }
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                BridgeLogger.d(TAG, "Send channels closed — send loop exiting cleanly")
            } catch (e: CancellationException) {
                throw e  // propagate coroutine cancellation normally
            }
        }
    }

    private fun startReceiveLoop(sock: DatagramSocket) {
        receiveJob = scope.launch {
            // BUG-104 FIX: same scheduling-priority boost as the send loop — a delayed
            // receive dispatch adds directly to end-to-end mouse latency.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(MAX_PACKET_SIZE)
            val dp = DatagramPacket(buf, buf.size)
            // BUG-091 FIX: isConnected is reused for the next session. Also honour this
            // job's cancellation so an old reader cannot spin on its closed socket after
            // an immediate reconnect publishes isConnected = true again.
            while (isConnected && coroutineContext.isActive) {
                try {
                    sock.receive(dp)
                    // Track the sender address for receiver-mode replies (PONG etc.)
                    (dp.socketAddress as? InetSocketAddress)?.let { lastSenderAddress = it }
                    val packet = PacketSerializer.deserialize(buf, dp.length) ?: continue
                    _incomingPackets.emit(packet)
                } catch (e: Exception) {
                    if (isConnected) BridgeLogger.w(TAG, "Receive error", e)
                }
            }
        }
    }
}
