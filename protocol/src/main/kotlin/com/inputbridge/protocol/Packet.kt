package com.inputbridge.protocol

/**
 * A wire-format packet in the InputBridge protocol.
 *
 * Binary layout (variable length):
 * ┌────────────┬──────────────┬──────────────┬────────────────┬────────────────────────┐
 * │  version   │   type_id    │  sequence_no │  timestamp_ms  │  payload               │
 * │  1 byte    │  1 byte      │  4 bytes     │  8 bytes       │  N bytes               │
 * └────────────┴──────────────┴──────────────┴────────────────┴────────────────────────┘
 * Total header = 14 bytes. Payload varies per type.
 *
 * The protocol is designed to be resilient to UDP packet loss:
 * - Each packet is self-contained and can be processed independently.
 * - Sequence numbers allow latency measurement and duplicate detection.
 * - No fragmentation — keep all packets under MTU (≈1400 bytes).
 */
data class Packet(
    /** Protocol version. Current: 1. */
    val version: Byte = PROTOCOL_VERSION,
    /** Packet type (see PacketType). */
    val type: PacketType,
    /** Monotonically increasing per-sender sequence number. */
    val sequenceNo: Int,
    /** Sender wall-clock time in milliseconds since epoch. */
    val timestampMs: Long,
    /** Type-specific payload bytes. */
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return version == other.version &&
            type == other.type &&
            sequenceNo == other.sequenceNo &&
            timestampMs == other.timestampMs &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + sequenceNo
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val PROTOCOL_VERSION: Byte = 1
        const val HEADER_SIZE = 14 // bytes
    }
}
