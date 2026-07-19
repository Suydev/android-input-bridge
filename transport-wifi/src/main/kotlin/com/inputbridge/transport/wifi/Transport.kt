package com.inputbridge.transport.wifi

import com.inputbridge.protocol.Packet
import kotlinx.coroutines.flow.Flow

/**
 * Contract for all transport implementations.
 *
 * Transports are responsible for:
 * - Establishing a connection to the peer (receiver) device.
 * - Sending [Packet] objects over the wire.
 * - Receiving [Packet] objects and exposing them through [incomingPackets].
 * - Managing reconnects automatically per [TransportConfig].
 * - Reporting their connection state via [connectionState].
 *
 * Implementations: [UdpTransport], [WifiDirectTransport]
 */
interface Transport {

    /** Current connection state. */
    val connectionState: Flow<ConnectionState>

    /** Packets received from the peer. Collect on a background dispatcher. */
    val incomingPackets: Flow<Packet>

    /**
     * Connect to the configured peer.
     * @return true if connected successfully.
     */
    suspend fun connect(): Boolean

    /**
     * Disconnect cleanly. Safe to call multiple times.
     */
    suspend fun disconnect()

    /**
     * Send a packet to the peer.
     * Non-blocking: packets are queued internally if the socket is busy.
     * Returns false if the queue is full or the transport is disconnected.
     */
    suspend fun send(packet: Packet): Boolean

    /** Whether the transport is currently connected. */
    val isConnected: Boolean
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
    object PairingRequired : ConnectionState()
}
