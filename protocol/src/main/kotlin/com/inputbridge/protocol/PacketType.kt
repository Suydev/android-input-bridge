package com.inputbridge.protocol

/**
 * All packet types in the InputBridge wire protocol.
 *
 * Protocol version: 1
 * Wire format: binary (see Packet.kt and PacketSerializer.kt)
 *
 * Packet IDs occupy one byte (0x00–0xFF).
 * IDs 0x00–0x1F are reserved for control/meta packets.
 * IDs 0x20–0x5F are reserved for input event packets.
 * IDs 0x60–0xFF are reserved for future extension.
 *
 * DO NOT CHANGE existing IDs — this breaks compatibility with paired devices.
 * To add a new type, append it with the next available ID.
 */
enum class PacketType(val id: Byte) {
    // ── Control packets (0x00–0x1F) ──────────────────────────────────────────
    PING(0x00),
    PONG(0x01),
    KEEP_ALIVE(0x02),
    PAIR_REQUEST(0x03),
    PAIR_RESPONSE(0x04),
    PAIR_CONFIRM(0x05),
    MODE_SWITCH(0x06),
    DISCONNECT(0x07),
    RECONNECT(0x08),
    ACK(0x09),
    ERROR(0x0A),

    // ── Input event packets (0x20–0x5F) ──────────────────────────────────────
    KEY_DOWN(0x20),
    KEY_UP(0x21),
    MOUSE_MOVE(0x22),
    MOUSE_DOWN(0x23),
    MOUSE_UP(0x24),
    SCROLL(0x25),
    TEXT_INPUT(0x26),
    MODIFIER_STATE(0x27),
    SPECIAL_ACTION(0x28);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Byte): PacketType? = byId[id]
    }
}
