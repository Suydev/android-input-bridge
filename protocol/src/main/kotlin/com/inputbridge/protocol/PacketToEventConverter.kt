package com.inputbridge.protocol

import com.inputbridge.core.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a received [Packet] back into an [InputEvent] on the receiver side.
 *
 * This is the inverse of [EventPacketFactory]: it uses [PacketSerializer]'s
 * payload parsers to reconstruct the original event from wire bytes.
 *
 * Returns null for control packets (PING, PONG, KEEP_ALIVE, DISCONNECT, etc.)
 * that carry no input event data, and for any packet with a malformed payload.
 *
 * Thread-safe: stateless object.
 *
 * BUG-140 FIX — latency: the previous implementation called [PacketSerializer]
 * parse helpers that returned `Pair`/`Triple`, allocating a small object on
 * every packet. On the 125 Hz mouse stream that is ~125 boxed allocations/sec
 * that only feed the GC and add no value. We now read the primitive fields
 * directly from the payload with a `ByteBuffer` wrap (no array copy) and build
 * the [InputEvent] inline, eliminating the per-packet `Pair`/`Triple` allocation.
 */
object PacketToEventConverter {

    private val ORDER = ByteOrder.BIG_ENDIAN

    /**
     * Convert a [Packet] to an [InputEvent], or null if this packet is not
     * an input event (e.g. PING, PONG, KEEP_ALIVE) or its payload is malformed.
     */
    fun toInputEvent(packet: Packet): InputEvent? {
        val payload = packet.payload
        return when (packet.type) {
            PacketType.KEY_DOWN -> {
                if (payload.size < 9) return null
                val buf = ByteBuffer.wrap(payload).order(ORDER)
                InputEvent.KeyDown(buf.int, buf.int, ModifierState.fromByte(buf.get()))
            }
            PacketType.KEY_UP -> {
                if (payload.size < 9) return null
                val buf = ByteBuffer.wrap(payload).order(ORDER)
                InputEvent.KeyUp(buf.int, buf.int, ModifierState.fromByte(buf.get()))
            }
            PacketType.MOUSE_MOVE -> {
                if (payload.size < 8) return null
                val buf = ByteBuffer.wrap(payload).order(ORDER)
                InputEvent.MouseMove(buf.float, buf.float)
            }
            PacketType.MOUSE_DOWN -> {
                val button = PacketSerializer.parseMouseButtonPayload(payload) ?: return null
                InputEvent.MouseButtonDown(button)
            }
            PacketType.MOUSE_UP -> {
                val button = PacketSerializer.parseMouseButtonPayload(payload) ?: return null
                InputEvent.MouseButtonUp(button)
            }
            PacketType.SCROLL -> {
                if (payload.size < 8) return null
                val buf = ByteBuffer.wrap(payload).order(ORDER)
                InputEvent.Scroll(buf.float, buf.float)
            }
            PacketType.TEXT_INPUT -> {
                val text = PacketSerializer.parseTextPayload(payload)
                InputEvent.TextInput(text)
            }
            PacketType.MODIFIER_STATE -> {
                val modifiers = PacketSerializer.parseModifierPayload(payload) ?: return null
                InputEvent.ModifierStateChanged(modifiers)
            }
            PacketType.SPECIAL_ACTION -> {
                val action = PacketSerializer.parseNavActionPayload(payload) ?: return null
                InputEvent.NavigationAction(action)
            }
            PacketType.CURSOR_GOTO -> {
                if (payload.size < 8) return null
                val buf = ByteBuffer.wrap(payload).order(ORDER)
                InputEvent.CursorGoto(buf.float, buf.float)
            }
            // Control packets — carry no input event data
            PacketType.PING,
            PacketType.PONG,
            PacketType.KEEP_ALIVE,
            PacketType.DISCONNECT,
            PacketType.RECONNECT,
            PacketType.ACK,
            PacketType.ERROR,
            PacketType.MODE_SWITCH,
            PacketType.PAIR_REQUEST,
            PacketType.PAIR_RESPONSE,
            PacketType.PAIR_CONFIRM -> null
        }
    }
}
