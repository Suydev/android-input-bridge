package com.inputbridge.protocol

import com.inputbridge.core.model.*

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
 */
object PacketToEventConverter {

    /**
     * Convert a [Packet] to an [InputEvent], or null if this packet is not
     * an input event (e.g. PING, PONG, KEEP_ALIVE) or its payload is malformed.
     */
    fun toInputEvent(packet: Packet): InputEvent? = when (packet.type) {
        PacketType.KEY_DOWN -> {
            val (keyCode, scanCode, modifiers) =
                PacketSerializer.parseKeyPayload(packet.payload) ?: return null
            InputEvent.KeyDown(keyCode, scanCode, modifiers)
        }
        PacketType.KEY_UP -> {
            val (keyCode, scanCode, modifiers) =
                PacketSerializer.parseKeyPayload(packet.payload) ?: return null
            InputEvent.KeyUp(keyCode, scanCode, modifiers)
        }
        PacketType.MOUSE_MOVE -> {
            val (dx, dy) = PacketSerializer.parseMouseMovePayload(packet.payload) ?: return null
            InputEvent.MouseMove(dx, dy)
        }
        PacketType.MOUSE_DOWN -> {
            val button = PacketSerializer.parseMouseButtonPayload(packet.payload) ?: return null
            InputEvent.MouseButtonDown(button)
        }
        PacketType.MOUSE_UP -> {
            val button = PacketSerializer.parseMouseButtonPayload(packet.payload) ?: return null
            InputEvent.MouseButtonUp(button)
        }
        PacketType.SCROLL -> {
            val (dx, dy) = PacketSerializer.parseScrollPayload(packet.payload) ?: return null
            InputEvent.Scroll(dx, dy)
        }
        PacketType.TEXT_INPUT -> {
            val text = PacketSerializer.parseTextPayload(packet.payload)
            InputEvent.TextInput(text)
        }
        PacketType.MODIFIER_STATE -> {
            val modifiers = PacketSerializer.parseModifierPayload(packet.payload) ?: return null
            InputEvent.ModifierStateChanged(modifiers)
        }
        PacketType.SPECIAL_ACTION -> {
            val action = PacketSerializer.parseNavActionPayload(packet.payload) ?: return null
            InputEvent.NavigationAction(action)
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
