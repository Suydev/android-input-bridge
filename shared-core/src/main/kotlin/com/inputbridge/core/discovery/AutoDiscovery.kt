package com.inputbridge.core.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import com.inputbridge.core.logging.BridgeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UDP broadcast-based auto-discovery for finding the receiver on the local network.
 *
 * Protocol:
 * - Discovery port: 54322 (different from the main data port 54321)
 * - Receiver broadcasts "INPUTBRIDGE_RECEIVER:<port>" every 3 seconds on the broadcast address
 * - Bridge listens on port 54322 and extracts the sender IP + port from the packet
 * - No handshake needed — just the broadcast message
 */
object AutoDiscovery {

    private const val TAG = "AutoDiscovery"
    const val DISCOVERY_PORT = 54322
    const val BROADCAST_MSG = "INPUTBRIDGE_RECEIVER"
    private const val BROADCAST_INTERVAL_MS = 3000L

    /**
     * Start broadcasting this device's presence as a receiver.
     * Call from a coroutine on Dispatchers.IO. Loops until the coroutine is cancelled.
     */
    suspend fun startBroadcasting(listenPort: Int) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true

            val message = "$BROADCAST_MSG:$listenPort".toByteArray(Charsets.UTF_8)
            val broadcastAddr = getBroadcastAddress(socket)

            while (true) {
                try {
                    val packet = DatagramPacket(
                        message, message.size,
                        broadcastAddr, DISCOVERY_PORT
                    )
                    socket.send(packet)
                } catch (e: Exception) {
                    BridgeLogger.w(TAG, "Broadcast send failed: ${e.message}")
                }
                kotlinx.coroutines.delay(BROADCAST_INTERVAL_MS)
            }
        } catch (e: Exception) {
            BridgeLogger.e(TAG, "startBroadcasting failed: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    /**
     * Listen for receiver broadcasts. Returns the receiver's IP and port.
     * BUG-XXX FIX: now a suspend function that checks coroutine cancellation.
     * The old blocking implementation would ignore cancellation for up to 10s
     * per timeout cycle. Now uses coroutineScope + launch to close socket on cancel.
     */
    suspend fun listenForReceiver(onFound: (ip: String, port: Int) -> Unit) = coroutineScope {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 3000
            bind(InetSocketAddress(DISCOVERY_PORT))
        }

        // Close socket when coroutine is cancelled so blocking receive() unblocks
        launch {
            try {
                while (isActive) {
                    kotlinx.coroutines.delay(1000)
                }
            } finally {
                socket.close()
            }
        }

        try {
            val buffer = ByteArray(256)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith(BROADCAST_MSG)) {
                        val parts = msg.split(":")
                        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 54321 else 54321
                        val senderIp = packet.address?.hostAddress ?: continue
                        onFound(senderIp, port)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal timeout, continue listening
                } catch (e: Exception) {
                    BridgeLogger.w(TAG, "Socket receive failed: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            BridgeLogger.e(TAG, "listenForReceiver failed: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun getBroadcastAddress(socket: DatagramSocket): InetAddress {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) return broadcast
                }
            }
        } catch (_: Exception) { }
        // Fallback: 255.255.255.255
        return InetAddress.getByName("255.255.255.255")
    }
}
