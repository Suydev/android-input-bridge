package com.inputbridge.core.model

/**
 * Sealed hierarchy representing every input event the bridge can produce.
 * This is the internal event model — transport-agnostic and protocol-agnostic.
 * All modules communicate through these types on the hot path.
 */
sealed class InputEvent {

    /** A physical key was pressed down. */
    data class KeyDown(
        val keyCode: Int,
        val scanCode: Int,
        val modifiers: ModifierState,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** A physical key was released. */
    data class KeyUp(
        val keyCode: Int,
        val scanCode: Int,
        val modifiers: ModifierState,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** Mouse moved by a relative delta from current position. */
    data class MouseMove(
        val dx: Float,
        val dy: Float,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** A mouse button was pressed. */
    data class MouseButtonDown(
        val button: MouseButton,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** A mouse button was released. */
    data class MouseButtonUp(
        val button: MouseButton,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** Scroll wheel turned. Positive = down/right, negative = up/left. */
    data class Scroll(
        val dx: Float,
        val dy: Float,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** Composed text string to inject (e.g. from IME or paste). */
    data class TextInput(
        val text: String,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** Current state of all modifier keys changed. */
    data class ModifierStateChanged(
        val modifiers: ModifierState,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()

    /** An Android-specific navigation action. */
    data class NavigationAction(
        val action: AndroidNavAction,
        val timestampNs: Long = System.nanoTime()
    ) : InputEvent()
}

/**
 * Bitmask-style data class for modifier key state.
 * Designed to be compact and fast to serialize.
 */
data class ModifierState(
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val capsLock: Boolean = false,
    val numLock: Boolean = false,
) {
    /** Pack into a single byte for wire format. */
    fun toByte(): Byte {
        var b = 0
        if (shift)    b = b or 0x01
        if (ctrl)     b = b or 0x02
        if (alt)      b = b or 0x04
        if (meta)     b = b or 0x08
        if (capsLock) b = b or 0x10
        if (numLock)  b = b or 0x20
        return b.toByte()
    }

    companion object {
        val NONE = ModifierState()

        fun fromByte(b: Byte): ModifierState {
            val i = b.toInt() and 0xFF
            return ModifierState(
                shift    = (i and 0x01) != 0,
                ctrl     = (i and 0x02) != 0,
                alt      = (i and 0x04) != 0,
                meta     = (i and 0x08) != 0,
                capsLock = (i and 0x10) != 0,
                numLock  = (i and 0x20) != 0,
            )
        }
    }
}

/** Mouse button identifiers. */
enum class MouseButton(val id: Byte) {
    LEFT(0),
    RIGHT(1),
    MIDDLE(2),
    BACK(3),
    FORWARD(4);

    companion object {
        fun fromId(id: Byte) = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

/** Android navigation actions that can be triggered remotely. */
enum class AndroidNavAction(val id: Byte) {
    BACK(0),
    HOME(1),
    RECENTS(2),
    NOTIFICATIONS(3),
    POWER(4),
    VOLUME_UP(5),
    VOLUME_DOWN(6),
    SCREENSHOT(7);

    companion object {
        fun fromId(id: Byte) = entries.firstOrNull { it.id == id } ?: BACK
    }
}
