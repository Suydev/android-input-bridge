package com.inputbridge.core.model

import org.junit.Assert.*
import org.junit.Test

class InputEventTest {

    @Test
    fun `ModifierState none has all flags false`() {
        val m = ModifierState.NONE
        assertFalse(m.shift)
        assertFalse(m.ctrl)
        assertFalse(m.alt)
        assertFalse(m.meta)
        assertFalse(m.capsLock)
        assertFalse(m.numLock)
    }

    @Test
    fun `ModifierState toByte and fromByte round-trips all combinations`() {
        // Test all meaningful combinations
        val combos = listOf(
            ModifierState(shift = true),
            ModifierState(ctrl = true),
            ModifierState(alt = true),
            ModifierState(meta = true),
            ModifierState(capsLock = true),
            ModifierState(numLock = true),
            ModifierState(shift = true, ctrl = true, alt = true),
            ModifierState(shift = true, ctrl = true, alt = true, meta = true, capsLock = true, numLock = true),
        )
        combos.forEach { original ->
            val restored = ModifierState.fromByte(original.toByte())
            assertEquals("Round-trip failed for $original", original, restored)
        }
    }

    @Test
    fun `MouseButton fromId returns correct button`() {
        assertEquals(MouseButton.LEFT,    MouseButton.fromId(0))
        assertEquals(MouseButton.RIGHT,   MouseButton.fromId(1))
        assertEquals(MouseButton.MIDDLE,  MouseButton.fromId(2))
    }

    @Test
    fun `MouseButton fromId defaults to LEFT for unknown id`() {
        assertEquals(MouseButton.LEFT, MouseButton.fromId(99))
    }

    @Test
    fun `AndroidNavAction fromId returns correct action`() {
        assertEquals(AndroidNavAction.BACK,    AndroidNavAction.fromId(0))
        assertEquals(AndroidNavAction.HOME,    AndroidNavAction.fromId(1))
        assertEquals(AndroidNavAction.RECENTS, AndroidNavAction.fromId(2))
    }

    @Test
    fun `KeyDown event has correct fields`() {
        val mods = ModifierState(shift = true)
        val event = InputEvent.KeyDown(keyCode = 65, scanCode = 0x04, modifiers = mods)
        assertEquals(65, event.keyCode)
        assertEquals(0x04, event.scanCode)
        assertTrue(event.modifiers.shift)
    }

    @Test
    fun `MouseMove event stores dx and dy`() {
        val event = InputEvent.MouseMove(dx = 5.0f, dy = -3.0f)
        assertEquals(5.0f, event.dx, 0.001f)
        assertEquals(-3.0f, event.dy, 0.001f)
    }

    @Test
    fun `Scroll event stores dx and dy`() {
        val event = InputEvent.Scroll(dx = 0f, dy = 2.5f)
        assertEquals(0f, event.dx, 0.001f)
        assertEquals(2.5f, event.dy, 0.001f)
    }

    @Test
    fun `TextInput stores text correctly`() {
        val event = InputEvent.TextInput("hello world")
        assertEquals("hello world", event.text)
    }
}
