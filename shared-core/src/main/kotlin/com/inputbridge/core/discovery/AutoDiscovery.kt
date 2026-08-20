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
 * Protocol (bidirectional, BUG-133 FIX):
 * - Discovery port: 54322 (different from the main data port 54321)
 * - Receiver broadcasts "INPUTBRIDGE_RECEIVER:<port>" every 3 seconds on every broadcast address.
 * - Bridge ALSO broadcasts "INPUTBRIDGE_QUERY" every 2 seconds on every broadcast address.
 * - The receiver listens for QUERY and replies "INPUTBRIDGE_RECEIVER:<port>" directly to the
 *   bridge's source address+port.
 * - The bridge listens for RECEIVER announcements AND the receiver's reply to QUERY.
 *
 * Making both sides both broadcast and listen guarantees discovery works even if one
 * direction's broadcast packet is dropped by the Wi-Fi stack (common on hotspots), so the
 * bridge connects without any manual IP or code entry.
 */
object AutoDiscovery {

    private const val TAG = "AutoDiscovery"
    const val DISCOVERY_PORT = 54322
    const val BROADCAST_MSG = "INPUTBRIDGE_RECEIVER"
    const val QUERY_MSG = "INPUTBRIDGE_QUERY"
    private const val BROADCAST_INTERVAL_MS = 3000L
    private const val QUERY_INTERVAL_MS = 2000L

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
            // BUG-130 FIX: broadcast on EVERY interface's broadcast address plus the
            // universal 255.255.255.255. The old code used only the first non-loopback
            // interface, which on a hotspot/client setup could be the wrong subnet and
            // the bridge never heard the announcement — leaving it stuck "Searching".
            val targets = getAllBroadcastAddresses()

            while (true) {
                for (addr in targets) {
                    try {
                        val packet = DatagramPacket(
                            message, message.size,
                            addr, DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (e: Exception) {
                        BridgeLogger.w(TAG, "Broadcast send to $addr failed: ${e.message}")
                    }
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
     * Bridge side: periodically broadcast a QUERY so any receiver that missed our listen
     * window (or whose own broadcast was dropped) answers us directly.
     */
    suspend fun startQuerying() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            val message = QUERY_MSG.toByteArray(Charsets.UTF_8)
            val targets = getAllBroadcastAddresses()
            while (true) {
                for (addr in targets) {
                    try {
                        socket.send(DatagramPacket(message, message.size, addr, DISCOVERY_PORT))
                    } catch (e: Exception) {
                        BridgeLogger.w(TAG, "Query send to $addr failed: ${e.message}")
                    }
                }
                kotlinx.coroutines.delay(QUERY_INTERVAL_MS)
            }
        } catch (e: Exception) {
            BridgeLogger.e(TAG, "startQuerying failed: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    /**
     * Receiver side: listen for QUERY broadcasts and reply directly to the querying bridge
     * with our RECEIVER announcement. This closes the loop when the bridge's listen socket
     * never received our periodic broadcast (dropped packet on the Wi-Fi stack).
     */
    suspend fun listenForQueriesAndRespond(listenPort: Int) = coroutineScope {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 2000
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        launch {
            try {
                while (isActive) kotlinx.coroutines.delay(1000)
            } finally {
                socket.close()
            }
        }
        val reply = "$BROADCAST_MSG:$listenPort".toByteArray(Charsets.UTF_8)
        try {
            val buffer = ByteArray(256)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg == QUERY_MSG) {
                        val from = packet.address ?: continue
                        BridgeLogger.i(TAG, "Received QUERY from $from — replying on $DISCOVERY_PORT")
                        try {
                            // Reply to the bridge's discovery listen port (54322), NOT the
                            // query's ephemeral source port, so the bridge's listener receives it.
                            socket.send(DatagramPacket(reply, reply.size, from, DISCOVERY_PORT))
                        } catch (e: Exception) {
                            BridgeLogger.w(TAG, "Query reply to $from failed: ${e.message}")
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal timeout, continue listening
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // BUG-148 FIX: one transient socket error previously terminated the
                    // whole discovery loop (break), so a single ICMP/network hiccup made
                    // discovery permanently dead until service restart. Keep listening,
                    // but back off briefly so a persistently-broken socket can't busy-loop.
                    BridgeLogger.w(TAG, "Query listen failed: ${e.message}")
                    kotlinx.coroutines.delay(1000)
                }
            }
        } finally {
            socket.close()
        }
    }

    /**
     * Listen for receiver broadcasts (and receiver replies to our QUERY).
     * Returns the receiver's IP and port.
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
                    // BUG-148 FIX: same as the query listener — a transient socket error
                    // must not permanently kill the bridge's discovery listener.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    BridgeLogger.w(TAG, "Socket receive failed: ${e.message}")
                    kotlinx.coroutines.delay(1000)
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

    /**
     * BUG-130 FIX: collect every usable broadcast address on the device — one per
     * up, non-loopback interface — plus the universal 255.255.255.255. Broadcasting
     * to all of them guarantees the peer on the active subnet actually receives the
     * announcement regardless of which interface the OS reports first.
     */
    private fun getAllBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) result.add(broadcast)
                }
            }
        } catch (_: Exception) { }
        result.add(InetAddress.getByName("255.255.255.255"))
        return result.distinct()
    }
}
