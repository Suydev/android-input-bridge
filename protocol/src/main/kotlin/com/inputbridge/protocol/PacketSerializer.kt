package com.inputbridge.protocol

import com.inputbridge.core.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes and deserializes [Packet] objects to/from raw bytes.
 *
 * All multi-byte integers are big-endian (network byte order).
 * This class has no mutable state and is safe to call from multiple threads.
 *
 * Performance note: [ByteBuffer] allocation is minimal — payloads are built
 * with fixed-size buffers per type. Do not add unnecessary intermediate objects.
 */
object PacketSerializer {

    private val BYTE_ORDER = ByteOrder.BIG_ENDIAN

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * Serialize a [Packet] to a [ByteArray] ready for transmission.
     * Returns null if the packet type is unknown.
     */
    fun serialize(packet: Packet): ByteArray {
        val payload = packet.payload
        val buf = ByteBuffer.allocate(Packet.HEADER_SIZE + payload.size)
            .order(BYTE_ORDER)
        buf.put(packet.version)
        buf.put(packet.type.id)
        buf.putInt(packet.sequenceNo)
        buf.putLong(packet.timestampMs)
        buf.put(payload)
        return buf.array()
    }

    // ── Deserialization ───────────────────────────────────────────────────────

    /**
     * Deserialize raw bytes to a [Packet].
     * Returns null if the data is malformed or the protocol version is unsupported.
     */
    fun deserialize(data: ByteArray): Packet? {
        if (data.size < Packet.HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(BYTE_ORDER)

        val version = buf.get()
        if (version != Packet.PROTOCOL_VERSION) return null

        val typeId = buf.get()
        val type = PacketType.fromId(typeId) ?: return null
        val seqNo = buf.int
        val timestampMs = buf.long

        val payloadSize = data.size - Packet.HEADER_SIZE
        val payload = ByteArray(payloadSize)
        if (payloadSize > 0) buf.get(payload)

        return Packet(version, type, seqNo, timestampMs, payload)
    }

    // ── Payload builders (input events → payload bytes) ───────────────────────

    fun buildKeyPayload(keyCode: Int, scanCode: Int, modifiers: ModifierState): ByteArray =
        ByteBuffer.allocate(9).order(BYTE_ORDER)
            .putInt(keyCode)
            .putInt(scanCode)
            .put(modifiers.toByte())
            .array()

    fun buildMouseMovePayload(dx: Float, dy: Float): ByteArray =
        ByteBuffer.allocate(8).order(BYTE_ORDER)
            .putFloat(dx)
            .putFloat(dy)
            .array()

    fun buildMouseButtonPayload(button: MouseButton): ByteArray =
        byteArrayOf(button.id)

    fun buildScrollPayload(dx: Float, dy: Float): ByteArray =
        ByteBuffer.allocate(8).order(BYTE_ORDER)
            .putFloat(dx)
            .putFloat(dy)
            .array()

    fun buildTextPayload(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun buildModifierPayload(modifiers: ModifierState): ByteArray =
        byteArrayOf(modifiers.toByte())

    fun buildNavActionPayload(action: AndroidNavAction): ByteArray =
        byteArrayOf(action.id)

    // ── Payload parsers (payload bytes → typed values) ────────────────────────

    fun parseKeyPayload(payload: ByteArray): Triple<Int, Int, ModifierState>? {
        if (payload.size < 9) return null
        val buf = ByteBuffer.wrap(payload).order(BYTE_ORDER)
        return Triple(buf.int, buf.int, ModifierState.fromByte(buf.get()))
    }

    fun parseMouseMovePayload(payload: ByteArray): Pair<Float, Float>? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(BYTE_ORDER)
        return Pair(buf.float, buf.float)
    }

    fun parseMouseButtonPayload(payload: ByteArray): MouseButton? {
        if (payload.isEmpty()) return null
        return MouseButton.fromId(payload[0])
    }

    fun parseScrollPayload(payload: ByteArray): Pair<Float, Float>? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(BYTE_ORDER)
        return Pair(buf.float, buf.float)
    }

    fun parseTextPayload(payload: ByteArray): String = payload.toString(Charsets.UTF_8)

    fun parseModifierPayload(payload: ByteArray): ModifierState? {
        if (payload.isEmpty()) return null
        return ModifierState.fromByte(payload[0])
    }

    fun parseNavActionPayload(payload: ByteArray): AndroidNavAction? {
        if (payload.isEmpty()) return null
        return AndroidNavAction.fromId(payload[0])
    }
}
