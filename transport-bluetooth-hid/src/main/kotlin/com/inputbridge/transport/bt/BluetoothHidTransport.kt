package com.inputbridge.transport.bt

import com.inputbridge.core.config.FeatureFlags
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.protocol.Packet
import com.inputbridge.transport.wifi.ConnectionState
import com.inputbridge.transport.wifi.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "BluetoothHidTransport"

/**
 * Bluetooth HID Device transport — Phase 6 implementation.
 *
 * When active, the Redmi 9 registers as a Bluetooth HID keyboard+mouse.
 * The OnePlus Pad Go sees it as a real input device and provides a system cursor.
 *
 * This is the ONLY path to a real hardware cursor without root.
 *
 * Current status: STUB — Phase 6.
 * The interface is defined so the rest of the codebase can reference it,
 * but all methods return false/no-op until Phase 6 implementation begins.
 *
 * Feature flag: [FeatureFlags.BLUETOOTH_HID_ENABLED]
 */
class BluetoothHidTransport : Transport {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)

    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()
    override val incomingPackets: Flow<Packet> = _incomingPackets.asSharedFlow()
    override val isConnected: Boolean = false

    override suspend fun connect(): Boolean {
        if (!FeatureFlags.BLUETOOTH_HID_ENABLED) {
            BridgeLogger.i(TAG, "Bluetooth HID disabled by feature flag")
            return false
        }
        BridgeLogger.w(TAG, "BluetoothHidTransport.connect() — Phase 6 not yet implemented")
        _connectionState.value = ConnectionState.Error("Phase 6 not implemented")
        return false
    }

    override suspend fun disconnect() {
        BridgeLogger.i(TAG, "BluetoothHidTransport.disconnect() — stub")
    }

    override suspend fun send(packet: Packet): Boolean {
        BridgeLogger.w(TAG, "BluetoothHidTransport.send() — stub, packet dropped")
        return false
    }
}
