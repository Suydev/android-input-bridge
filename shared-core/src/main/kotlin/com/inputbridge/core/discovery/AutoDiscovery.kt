package com.inputbridge.core.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

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

    const val DISCOVERY_PORT = 54322
    const val BROADCAST_MSG = "INPUTBRIDGE_RECEIVER"
    private const val BROADCAST_INTERVAL_MS = 3000L

    /**
     * Start broadcasting this device's presence as a receiver.
     * Call from a coroutine on Dispatchers.IO. Loops until the coroutine is cancelled.
     */
    suspend fun startBroadcasting(listenPort: Int) {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true

        val message = "$BROADCAST_MSG:$listenPort".toByteArray()
        val broadcastAddr = getBroadcastAddress(socket)

        while (true) {
            try {
                val packet = DatagramPacket(
                    message, message.size,
                    broadcastAddr, DISCOVERY_PORT
                )
                socket.send(packet)
            } catch (_: Exception) { }
            kotlinx.coroutines.delay(BROADCAST_INTERVAL_MS)
        }
    }

    /**
     * Listen for receiver broadcasts. Returns the receiver's IP and port.
     * Call from a coroutine on Dispatchers.IO.
     */
    fun listenForReceiver(onFound: (ip: String, port: Int) -> Unit) {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.soTimeout = 10000 // 10s timeout for retry
        socket.bind(InetSocketAddress(DISCOVERY_PORT))

        val buffer = ByteArray(256)
        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.startsWith(BROADCAST_MSG)) {
                        val parts = msg.split(":")
                        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 54321 else 54321
                        val senderIp = packet.address.hostAddress ?: continue
                        onFound(senderIp, port)
                    }
                } catch (_: java.net.SocketTimeoutException) { }
            }
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
