package com.inputbridge.bridge.service

import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.protocol.Packet
import com.inputbridge.protocol.PacketToEventConverter
import com.inputbridge.protocol.PacketType

private const val TAG = "BridgeInputInjector"

/**
 * Handles incoming input event packets from the receiver (reverse trackpad mode).
 *
 * When the receiver tablet is in trackpad mode, it captures touch input and sends
 * CursorGoto / MouseMove / Scroll packets back to the bridge. This injector
 * converts those packets into InputEvents and routes them to
 * [BridgeAccessibilityService.injectInputEvent] for local injection via dispatchGesture().
 *
 * Thread safety: called from BridgeService's incoming-packet collector on IO dispatcher.
 * BridgeAccessibilityService.injectInputEvent dispatches gestures on the main thread internally.
 */
object BridgeInputInjector {

    /**
     * Process an incoming packet from the receiver.
     * Returns true if the packet was an input event that was handled.
     */
    fun handlePacket(packet: Packet): Boolean {
        if (!BridgeAccessibilityService.isRunning()) {
            BridgeLogger.d(TAG, "Accessibility service not running — dropping reverse event")
            return false
        }

        val event: InputEvent = when (packet.type) {
            // Input event packets that the bridge may receive in reverse-trackpad mode
            PacketType.CURSOR_GOTO,
            PacketType.MOUSE_MOVE,
            PacketType.MOUSE_DOWN,
            PacketType.MOUSE_UP,
            PacketType.SCROLL,
            PacketType.KEY_DOWN,
            PacketType.KEY_UP,
            PacketType.TEXT_INPUT,
            PacketType.MODIFIER_STATE,
            PacketType.SPECIAL_ACTION -> {
                PacketToEventConverter.toInputEvent(packet)
            }
            // Control packets — not handled by the reverse-trackpad injector
            PacketType.PING,
            PacketType.PONG,
            PacketType.KEEP_ALIVE,
            PacketType.PAIR_REQUEST,
            PacketType.PAIR_RESPONSE,
            PacketType.PAIR_CONFIRM,
            PacketType.MODE_SWITCH,
            PacketType.DISCONNECT,
            PacketType.RECONNECT,
            PacketType.ACK,
            PacketType.ERROR -> null
        } ?: return false

        BridgeAccessibilityService.instance?.injectInputEvent(event)
        return true
    }
}
