package com.inputbridge.protocol

import com.inputbridge.core.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [PacketSerializer] — the most critical component on the hot path.
 * These tests ensure round-trip fidelity for every packet type.
 */
class PacketSerializerTest {

    private val factory = EventPacketFactory()

    // ── Round-trip tests ──────────────────────────────────────────────────────

    @Test
    fun `serialize and deserialize key down packet`() {
        val modifiers = ModifierState(shift = true, ctrl = false)
        val payload = PacketSerializer.buildKeyPayload(65, 0x04, modifiers)
        val packet = Packet(type = PacketType.KEY_DOWN, sequenceNo = 1, timestampMs = 1000L, payload = payload)

        val bytes = PacketSerializer.serialize(packet)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        assertEquals(PacketType.KEY_DOWN, restored!!.type)
        assertEquals(1, restored.sequenceNo)
        assertEquals(1000L, restored.timestampMs)

        val (keyCode, scanCode, mods) = PacketSerializer.parseKeyPayload(restored.payload)!!
        assertEquals(65, keyCode)
        assertEquals(0x04, scanCode)
        assertTrue(mods.shift)
        assertFalse(mods.ctrl)
    }

    @Test
    fun `serialize and deserialize mouse move packet`() {
        val payload = PacketSerializer.buildMouseMovePayload(12.5f, -7.25f)
        val packet = Packet(type = PacketType.MOUSE_MOVE, sequenceNo = 2, timestampMs = 2000L, payload = payload)

        val bytes = PacketSerializer.serialize(packet)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        val (dx, dy) = PacketSerializer.parseMouseMovePayload(restored!!.payload)!!
        assertEquals(12.5f, dx, 0.001f)
        assertEquals(-7.25f, dy, 0.001f)
    }

    @Test
    fun `serialize and deserialize scroll packet`() {
        val payload = PacketSerializer.buildScrollPayload(0f, 3.0f)
        val packet = Packet(type = PacketType.SCROLL, sequenceNo = 3, timestampMs = 3000L, payload = payload)

        val bytes = PacketSerializer.serialize(packet)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        val (dx, dy) = PacketSerializer.parseScrollPayload(restored!!.payload)!!
        assertEquals(0f, dx, 0.001f)
        assertEquals(3.0f, dy, 0.001f)
    }

    @Test
    fun `serialize and deserialize text input packet`() {
        val text = "Hello, World! 🎉"
        val payload = PacketSerializer.buildTextPayload(text)
        val packet = Packet(type = PacketType.TEXT_INPUT, sequenceNo = 4, timestampMs = 4000L, payload = payload)

        val bytes = PacketSerializer.serialize(packet)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        assertEquals(text, PacketSerializer.parseTextPayload(restored!!.payload))
    }

    @Test
    fun `serialize and deserialize mouse button packet`() {
        val payload = PacketSerializer.buildMouseButtonPayload(MouseButton.RIGHT)
        val packet = Packet(type = PacketType.MOUSE_DOWN, sequenceNo = 5, timestampMs = 5000L, payload = payload)

        val bytes = PacketSerializer.serialize(packet)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        assertEquals(MouseButton.RIGHT, PacketSerializer.parseMouseButtonPayload(restored!!.payload))
    }

    @Test
    fun `serialize and deserialize navigation action packet`() {
        val payload = PacketSerializer.buildNavActionPayload(AndroidNavAction.BACK)
        val packet = Packet(type = PacketType.SPECIAL_ACTION, sequenceNo = 6, timestampMs = 6000L, payload = payload)

        val bytes = PacketSerializer.serialize(packet)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        assertEquals(AndroidNavAction.BACK, PacketSerializer.parseNavActionPayload(restored!!.payload))
    }

    @Test
    fun `serialize and deserialize ping packet`() {
        val ping = factory.makePing()
        val bytes = PacketSerializer.serialize(ping)
        val restored = PacketSerializer.deserialize(bytes)

        assertNotNull(restored)
        assertEquals(PacketType.PING, restored!!.type)
    }

    // ── Header tests ──────────────────────────────────────────────────────────

    @Test
    fun `deserialize returns null for too-short data`() {
        assertNull(PacketSerializer.deserialize(ByteArray(5)))
    }

    @Test
    fun `deserialize returns null for wrong protocol version`() {
        val bytes = ByteArray(Packet.HEADER_SIZE) { 0 }
        bytes[0] = 99 // wrong version
        assertNull(PacketSerializer.deserialize(bytes))
    }

    @Test
    fun `deserialize returns null for unknown packet type`() {
        val bytes = ByteArray(Packet.HEADER_SIZE) { 0 }
        bytes[0] = Packet.PROTOCOL_VERSION
        bytes[1] = 0xFF.toByte() // unknown type
        assertNull(PacketSerializer.deserialize(bytes))
    }

    // ── Modifier state tests ──────────────────────────────────────────────────

    @Test
    fun `modifier state round-trips through byte`() {
        val original = ModifierState(shift = true, ctrl = true, alt = false, meta = false, capsLock = true)
        val restored = ModifierState.fromByte(original.toByte())
        assertEquals(original, restored)
    }

    @Test
    fun `modifier none state is all false`() {
        val m = ModifierState.NONE
        assertFalse(m.shift); assertFalse(m.ctrl); assertFalse(m.alt); assertFalse(m.meta)
    }

    // ── EventPacketFactory tests ──────────────────────────────────────────────

    @Test
    fun `factory sequence numbers are monotonically increasing`() {
        val f = EventPacketFactory()
        val p1 = f.makePing()
        val p2 = f.makePing()
        val p3 = f.makePing()
        assertTrue(p2.sequenceNo > p1.sequenceNo)
        assertTrue(p3.sequenceNo > p2.sequenceNo)
    }

    @Test
    fun `factory fromEvent returns correct type for KeyDown`() {
        val f = EventPacketFactory()
        val event = InputEvent.KeyDown(65, 0x04, ModifierState.NONE)
        val packet = f.fromEvent(event)
        assertNotNull(packet)
        assertEquals(PacketType.KEY_DOWN, packet!!.type)
    }
}
