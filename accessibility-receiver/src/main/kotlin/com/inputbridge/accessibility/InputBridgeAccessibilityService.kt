package com.inputbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.ModifierState
import com.inputbridge.diagnostics.DiagnosticsManager

private const val TAG = "AccessibilityService"

/**
 * InputBridge Accessibility Service — the core of the receiver app's input injection.
 *
 * IMPORTANT: This is NOT a hardware cursor. Accessibility services inject synthetic
 * gestures and events, not kernel-level input. The UI must make this distinction clear.
 *
 * Capabilities:
 * - Tap anywhere on screen (dispatchGesture)
 * - Swipe / drag (multi-step gesture)
 * - Scroll (gesture-based)
 * - Text input via ACTION_SET_TEXT on the focused node, or clipboard paste fallback
 * - Keyboard keys: printable characters, backspace, enter, arrows, Ctrl shortcuts
 * - Navigation: BACK, HOME, RECENTS, NOTIFICATIONS
 *
 * Limitations (communicated to the user):
 * - No real mouse pointer
 * - Cannot click inside secure windows (e.g. PIN entry on lockscreen)
 * - Some system UI elements may not respond to synthetic gestures
 *
 * Commands are received through [AccessibilityCommandBus].
 */
@RequiresApi(Build.VERSION_CODES.N)
class InputBridgeAccessibilityService : AccessibilityService() {

    companion object {
        /** Singleton reference — set when service is connected, cleared on unbind. */
        @Volatile
        var instance: InputBridgeAccessibilityService? = null
            private set

        fun isRunning() = instance != null

        const val TAP_DURATION_MS = 50L
        const val LONG_PRESS_DURATION_MS = 600L
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        BridgeLogger.i(TAG, "Accessibility service connected")

        // Get real screen dimensions and pass them to the command bus so
        // mouse cursor clamping uses actual device dimensions.
        val (w, h) = getRealScreenSize()
        AccessibilityCommandBus.setScreenSize(w, h)
        BridgeLogger.i(TAG, "Screen size: ${w}×${h}")

        AccessibilityCommandBus.setService(this)

        DiagnosticsManager.update {
            copy(
                accessibilityEnabled = true,
                accessibilityPermissionGranted = true,
                accessibilityMode = "Accessibility",
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityCommandBus.clearService()
        DiagnosticsManager.update {
            copy(accessibilityEnabled = false, accessibilityMode = "None")
        }
        BridgeLogger.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event observation needed for input injection.
        // Future: macro recording, UI inspection.
    }

    override fun onInterrupt() {
        BridgeLogger.w(TAG, "Accessibility service interrupted")
    }

    // ── Screen size ───────────────────────────────────────────────────────────

    private fun getRealScreenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "Could not get screen size, using default", e)
            Pair(1080, 2400)
        }
    }

    // ── Gesture injection ─────────────────────────────────────────────────────

    /**
     * Dispatch a tap gesture at the given screen coordinates.
     */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatch a swipe from (x1,y1) to (x2,y2) over [durationMs] milliseconds.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200L) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatch a long-press gesture (press and hold).
     */
    fun longPress(x: Float, y: Float, durationMs: Long = LONG_PRESS_DURATION_MS) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Navigation actions ────────────────────────────────────────────────────

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ── Keyboard injection ────────────────────────────────────────────────────

    /**
     * Inject a key event into the currently focused accessibility node.
     *
     * Handles:
     * - Printable characters: resolved via Android's KeyEvent.unicodeChar
     * - Backspace / Forward-delete: removes character at cursor
     * - Enter: performs click/action on the focused node (submits form or inserts newline)
     * - Arrow keys: moves cursor by character/word granularity
     * - Home/End: moves cursor to beginning/end of line
     * - Ctrl+A/C/V/X: select-all, copy, paste, cut
     * - Escape: navigate back
     */
    fun injectKeyCode(keyCode: Int, modifiers: ModifierState) {
        // Ctrl shortcuts — handle before printable character resolution
        if (modifiers.ctrl) {
            handleCtrlKey(keyCode)
            return
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                // Backspace: remove character before cursor (or delete selection)
                val focused = getFocused() ?: return
                val text = focused.text?.toString() ?: return
                val selStart = focused.textSelectionStart.coerceIn(0, text.length)
                val selEnd = focused.textSelectionEnd.coerceIn(selStart, text.length)
                val newText = if (selStart < selEnd) {
                    // Delete entire selection
                    text.substring(0, selStart) + text.substring(selEnd)
                } else if (selStart > 0) {
                    // Delete one character before cursor
                    text.substring(0, selStart - 1) + text.substring(selStart)
                } else return
                setFocusedText(focused, newText)
            }

            KeyEvent.KEYCODE_FORWARD_DEL -> {
                // Delete key: remove character after cursor
                val focused = getFocused() ?: return
                val text = focused.text?.toString() ?: return
                val selStart = focused.textSelectionStart.coerceIn(0, text.length)
                val selEnd = focused.textSelectionEnd.coerceIn(selStart, text.length)
                val newText = if (selStart < selEnd) {
                    text.substring(0, selStart) + text.substring(selEnd)
                } else if (selStart < text.length) {
                    text.substring(0, selStart) + text.substring(selStart + 1)
                } else return
                setFocusedText(focused, newText)
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val focused = getFocused()
                if (focused != null && focused.isEditable) {
                    // Try to perform IME action (submit/go/search), fall back to newline
                    val didClick = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!didClick) {
                        injectCharacterIntoFocused(focused, '\n')
                    }
                } else {
                    // No focused text field — act as a global click/confirm
                    focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }

            KeyEvent.KEYCODE_TAB -> {
                // Move focus to next element
                val root = rootInActiveWindow
                root?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleArrowKey(
                    forward = false,
                    granularity = if (modifiers.ctrl)
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                    else AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
                    extendSelection = modifiers.shift,
                )
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleArrowKey(
                    forward = true,
                    granularity = if (modifiers.ctrl)
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                    else AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
                    extendSelection = modifiers.shift,
                )
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                handleArrowKey(
                    forward = false,
                    granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                    extendSelection = modifiers.shift,
                )
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                handleArrowKey(
                    forward = true,
                    granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                    extendSelection = modifiers.shift,
                )
            }

            KeyEvent.KEYCODE_MOVE_HOME -> {
                handleArrowKey(
                    forward = false,
                    granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                    extendSelection = modifiers.shift,
                )
            }

            KeyEvent.KEYCODE_MOVE_END -> {
                handleArrowKey(
                    forward = true,
                    granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                    extendSelection = modifiers.shift,
                )
            }

            else -> {
                // Attempt to resolve a printable Unicode character from the keyCode
                val metaState = buildMetaState(modifiers)
                val kev = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
                val unicode = kev.unicodeChar
                if (unicode != 0) {
                    val focused = getFocused()
                    if (focused != null) {
                        injectCharacterIntoFocused(focused, unicode.toChar())
                    } else {
                        BridgeLogger.d(TAG, "No focused text node for char '${unicode.toChar()}'")
                    }
                } else {
                    BridgeLogger.d(TAG, "No printable char for keyCode=$keyCode")
                }
            }
        }
    }

    /**
     * Inject a complete text string into the focused text field.
     * Uses ACTION_SET_TEXT (append at cursor) as the primary strategy.
     * Falls back to clipboard paste if the node does not support ACTION_SET_TEXT.
     */
    fun injectText(text: String) {
        val focused = getFocused()
        if (focused != null) {
            val current = focused.text?.toString() ?: ""
            val selStart = focused.textSelectionStart.coerceIn(0, current.length)
            val selEnd = focused.textSelectionEnd.coerceIn(selStart, current.length)
            val newText = current.substring(0, selStart) + text + current.substring(selEnd)
            val bundle = Bundle()
            bundle.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText,
            )
            val set = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (set) return
        }
        // Fallback: clipboard paste
        try {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("InputBridge", text))
            focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                ?: BridgeLogger.w(TAG, "No focused node for clipboard paste")
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "Text injection failed", e)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Get the currently input-focused accessibility node, or null. */
    private fun getFocused(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** Insert a single character at the cursor position in a focused text node. */
    private fun injectCharacterIntoFocused(focused: AccessibilityNodeInfo, ch: Char) {
        val text = focused.text?.toString() ?: ""
        val selStart = focused.textSelectionStart.coerceIn(0, text.length)
        val selEnd = focused.textSelectionEnd.coerceIn(selStart, text.length)
        val newText = text.substring(0, selStart) + ch + text.substring(selEnd)
        setFocusedText(focused, newText)
    }

    /** Set text on a focused accessibility node via ACTION_SET_TEXT. */
    private fun setFocusedText(focused: AccessibilityNodeInfo, text: String) {
        val bundle = Bundle()
        bundle.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text,
        )
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    /** Handle Ctrl+key shortcuts. */
    private fun handleCtrlKey(keyCode: Int) {
        val focused = getFocused()
        when (keyCode) {
            KeyEvent.KEYCODE_A -> focused?.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
            KeyEvent.KEYCODE_C -> focused?.performAction(AccessibilityNodeInfo.ACTION_COPY)
            KeyEvent.KEYCODE_V -> focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            KeyEvent.KEYCODE_X -> focused?.performAction(AccessibilityNodeInfo.ACTION_CUT)
            else -> BridgeLogger.d(TAG, "Ctrl+${KeyEvent.keyCodeToString(keyCode)} not handled")
        }
    }

    /** Move the cursor in the focused node by the given granularity. */
    private fun handleArrowKey(
        forward: Boolean,
        granularity: Int,
        extendSelection: Boolean,
    ) {
        val focused = getFocused() ?: return
        val action = if (forward)
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        else
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extendSelection)
        }
        focused.performAction(action, args)
    }

    /**
     * Build an Android meta-state integer from our [ModifierState].
     * Used to resolve printable characters from key codes via KeyEvent.unicodeChar.
     */
    private fun buildMetaState(modifiers: ModifierState): Int {
        var meta = 0
        if (modifiers.shift || modifiers.capsLock) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (modifiers.ctrl)  meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (modifiers.alt)   meta = meta or KeyEvent.META_ALT_ON  or KeyEvent.META_ALT_LEFT_ON
        if (modifiers.meta)  meta = meta or KeyEvent.META_META_ON
        return meta
    }
}
