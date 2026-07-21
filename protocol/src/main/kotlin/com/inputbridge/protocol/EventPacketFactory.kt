package com.inputbridge.protocol

import com.inputbridge.core.model.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Converts [InputEvent] objects to [Packet] objects ready for transmission.
 *
 * Sequence numbers are generated atomically per factory instance.
 * Create one factory per transport sender and share it across threads safely.
 *
 * This is on the hot path — keep it fast. No allocations beyond ByteArray payload.
 */
class EventPacketFactory {

    private val sequenceCounter = AtomicInteger(0)

    private fun nextSeq() = sequenceCounter.getAndIncrement()
    private fun nowMs() = System.currentTimeMillis()

    fun fromEvent(event: InputEvent): Packet? = when (event) {
        is InputEvent.KeyDown -> Packet(
            type = PacketType.KEY_DOWN,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildKeyPayload(event.keyCode, event.scanCode, event.modifiers)
        )
        is InputEvent.KeyUp -> Packet(
            type = PacketType.KEY_UP,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildKeyPayload(event.keyCode, event.scanCode, event.modifiers)
        )
        is InputEvent.MouseMove -> Packet(
            type = PacketType.MOUSE_MOVE,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildMouseMovePayload(event.dx, event.dy)
        )
        is InputEvent.MouseButtonDown -> Packet(
            type = PacketType.MOUSE_DOWN,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildMouseButtonPayload(event.button)
        )
        is InputEvent.MouseButtonUp -> Packet(
            type = PacketType.MOUSE_UP,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildMouseButtonPayload(event.button)
        )
        is InputEvent.Scroll -> Packet(
            type = PacketType.SCROLL,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildScrollPayload(event.dx, event.dy)
        )
        is InputEvent.TextInput -> Packet(
            type = PacketType.TEXT_INPUT,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildTextPayload(event.text)
        )
        is InputEvent.ModifierStateChanged -> Packet(
            type = PacketType.MODIFIER_STATE,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildModifierPayload(event.modifiers)
        )
        is InputEvent.NavigationAction -> Packet(
            type = PacketType.SPECIAL_ACTION,
            sequenceNo = nextSeq(),
            timestampMs = nowMs(),
            payload = PacketSerializer.buildNavActionPayload(event.action)
        )
    }

    fun makePing(): Packet = Packet(
        type = PacketType.PING,
        sequenceNo = nextSeq(),
        timestampMs = nowMs()
    )

    fun makePong(pingSeq: Int): Packet = Packet(
        type = PacketType.PONG,
        sequenceNo = nextSeq(),
        timestampMs = nowMs(),
        payload = ByteArray(4).also {
            it[0] = (pingSeq shr 24).toByte()
            it[1] = (pingSeq shr 16).toByte()
            it[2] = (pingSeq shr 8).toByte()
            it[3] = pingSeq.toByte()
        }
    )

    fun makeKeepAlive(): Packet = Packet(
        type = PacketType.KEEP_ALIVE,
        sequenceNo = nextSeq(),
        timestampMs = nowMs()
    )

    fun makeDisconnect(): Packet = Packet(
        type = PacketType.DISCONNECT,
        sequenceNo = nextSeq(),
        timestampMs = nowMs()
    )

    /** Bridge → Receiver: initiate pairing, carrying the PIN the user entered. */
    fun makePairRequest(pin: String): Packet = Packet(
        type = PacketType.PAIR_REQUEST,
        sequenceNo = nextSeq(),
        timestampMs = nowMs(),
        payload = PacketSerializer.buildPairRequestPayload(pin)
    )

    /** Receiver → Bridge: respond to pairing request (accepted or rejected). */
    fun makePairResponse(accepted: Boolean): Packet = Packet(
        type = PacketType.PAIR_RESPONSE,
        sequenceNo = nextSeq(),
        timestampMs = nowMs(),
        payload = PacketSerializer.buildPairResponsePayload(accepted)
    )

    /** Bridge → Receiver: acknowledge that PAIR_RESPONSE was received; pairing complete. */
    fun makePairConfirm(): Packet = Packet(
        type = PacketType.PAIR_CONFIRM,
        sequenceNo = nextSeq(),
        timestampMs = nowMs()
    )
}
