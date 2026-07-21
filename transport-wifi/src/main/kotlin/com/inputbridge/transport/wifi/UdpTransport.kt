package com.inputbridge.transport.wifi

import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.protocol.Packet
import com.inputbridge.protocol.PacketSerializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

private const val TAG = "UdpTransport"
private const val MAX_PACKET_SIZE = 1400  // stay safely under Ethernet MTU
private const val SEND_QUEUE_CAPACITY = 128

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
    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 128)
    private val sendChannel = kotlinx.coroutines.channels.Channel<ByteArray>(SEND_QUEUE_CAPACITY)

    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()
    override val incomingPackets: Flow<Packet> = _incomingPackets.asSharedFlow()
    override var isConnected: Boolean = false
        private set

    private var socket: DatagramSocket? = null
    private var sendJob: Job? = null
    private var receiveJob: Job? = null

    /**
     * In receiver mode: the address of the most recently seen sender.
     * Used to send PONG and other control replies back to the bridge.
     * Updated atomically on every received datagram.
     */
    @Volatile private var lastSenderAddress: InetSocketAddress? = null

    override suspend fun connect(): Boolean {
        if (isConnected) return true
        _connectionState.value = ConnectionState.Connecting
        return try {
            val sock = if (isSender) {
                DatagramSocket().also { it.soTimeout = 0 }
            } else {
                DatagramSocket(config.port) // receiver binds to port
            }
            socket = sock
            startSendLoop(sock)
            startReceiveLoop(sock)
            isConnected = true
            _connectionState.value = ConnectionState.Connected
            BridgeLogger.i(TAG, "UDP transport connected (sender=$isSender port=${config.port})")
            true
        } catch (e: Exception) {
            BridgeLogger.e(TAG, "UDP connect failed", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            false
        }
    }

    override suspend fun disconnect() {
        if (!isConnected) return
        isConnected = false
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
        return sendChannel.trySend(bytes).isSuccess
    }

    private fun startSendLoop(sock: DatagramSocket) {
        sendJob = scope.launch {
            if (isSender) {
                // Bridge (sender) mode: target IP is fixed from config
                val targetAddress = InetAddress.getByName(config.targetIp)
                for (bytes in sendChannel) {
                    try {
                        val dp = DatagramPacket(bytes, bytes.size, targetAddress, config.port)
                        sock.send(dp)
                    } catch (e: Exception) {
                        if (isConnected) BridgeLogger.w(TAG, "Send error", e)
                    }
                }
            } else {
                // Receiver mode: send replies back to the last seen sender address.
                // If no packet has arrived yet, the send is a no-op.
                for (bytes in sendChannel) {
                    val addr = lastSenderAddress
                    if (addr == null) {
                        BridgeLogger.d(TAG, "Receiver send skipped — no sender address seen yet")
                        continue
                    }
                    try {
                        val dp = DatagramPacket(bytes, bytes.size, addr.address, addr.port)
                        sock.send(dp)
                    } catch (e: Exception) {
                        if (isConnected) BridgeLogger.w(TAG, "Reply send error", e)
                    }
                }
            }
        }
    }

    private fun startReceiveLoop(sock: DatagramSocket) {
        receiveJob = scope.launch {
            val buf = ByteArray(MAX_PACKET_SIZE)
            val dp = DatagramPacket(buf, buf.size)
            while (isConnected) {
                try {
                    sock.receive(dp)
                    // Track the sender address for receiver-mode replies (PONG etc.)
                    (dp.socketAddress as? InetSocketAddress)?.let { lastSenderAddress = it }
                    val packet = PacketSerializer.deserialize(buf.copyOf(dp.length)) ?: continue
                    _incomingPackets.emit(packet)
                } catch (e: Exception) {
                    if (isConnected) BridgeLogger.w(TAG, "Receive error", e)
                }
            }
        }
    }
}
