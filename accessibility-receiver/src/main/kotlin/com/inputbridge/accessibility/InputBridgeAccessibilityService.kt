package com.inputbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
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
        const val CONTINUOUS_STROKE_DURATION_MS = 60_000L // 60s max for continuous gesture
        const val MAX_STROKES_PER_GESTURE = 20 // Android limit
    }

    // ── Continuous gesture state (for mouse drag) ───────────────────────────
    private var currentStrokePath: Path? = null
    private var currentStrokeStartTime: Long = 0L
    private var strokeCount: Int = 0

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        BridgeLogger.i(TAG, "Accessibility service connected")

        // Get real screen dimensions and pass them to the command bus so
        // mouse cursor clamping uses actual device dimensions.
        // BUG-137 FIX: use realScreenSize() (maximumWindowMetrics) so the cursor covers the
        // full physical display, not just the current window bounds.
        val size = realScreenSize(this)
        AccessibilityCommandBus.setScreenSize(size.x, size.y)
        BridgeLogger.i(TAG, "Screen size: ${size.x}×${size.y}")

        AccessibilityCommandBus.setService(this)
        BridgeLogger.i(TAG, "AccessibilityCommandBus service attached — ready for injection")

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

    // BUG-137 FIX: real screen size now comes from the top-level realScreenSize() helper
    // (ScreenMetrics.kt), which uses maximumWindowMetrics for the true full-screen bounds.

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

    /**
     * Start or continue a continuous stroke for mouse drag operations.
     * Uses willContinue=true to keep the gesture alive for subsequent updates.
     * Only one active gesture at a time — new call cancels in-progress gesture.
     *
     * BUG-XXX FIX: cap path to MAX_STROKES_PER_GESTURE segments. Each call appends
     * a new lineTo and re-dispatches the ENTIRE accumulated path. After 20 segments
     * the path is reset to avoid unbounded memory growth and system gesture limits.
     */
    fun continueStroke(startX: Float, startY: Float, endX: Float, endY: Float, willContinue: Boolean, sessionId: Long) {
        // BUG-121 FIX (audit K): drop stale continuations that run after the drag ended.
        // The token is bumped on every MouseButtonDown/UP(LEFT); a continuation launched
        // before the up but executed after it would otherwise see currentStrokePath==null
        // and start a fresh open stroke that is never ended.
        if (sessionId != AccessibilityCommandBus.dragSessionId) {
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()

        if (currentStrokePath == null || !willContinue) {
            // Start new stroke
            currentStrokePath = Path().apply { moveTo(startX, startY) }
            currentStrokeStartTime = now
            strokeCount = 0
        }

        currentStrokePath?.lineTo(endX, endY)
        strokeCount++

        // BUG-XXX: cap at MAX_STROKES_PER_GESTURE — reset path to avoid unbounded growth
        if (strokeCount >= MAX_STROKES_PER_GESTURE) {
            // Dispatch final segment, then start fresh from current position
            val finalPath = currentStrokePath ?: return
            val finalDuration = (now - currentStrokeStartTime).coerceIn(1L, CONTINUOUS_STROKE_DURATION_MS)
            val finalStroke = GestureDescription.StrokeDescription(finalPath, 0, finalDuration, false)
            val finalGesture = GestureDescription.Builder().addStroke(finalStroke).build()
            dispatchGesture(finalGesture, null, null)
            // Reset for next segment batch
            currentStrokePath = Path().apply { moveTo(endX, endY) }
            currentStrokeStartTime = now
            strokeCount = 0
            return
        }

        val path = currentStrokePath ?: return
        val duration = (now - currentStrokeStartTime).coerceIn(1L, CONTINUOUS_STROKE_DURATION_MS)
        val strokeDuration = if (willContinue) duration else TAP_DURATION_MS

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            strokeDuration,
            willContinue
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, null, null)
    }

    /**
     * End the current continuous stroke.
     * BUG-XXX FIX: dispatch a final gesture with willContinue=false so the system
     * releases gesture resources instead of waiting for continuation that never arrives.
     */
    fun endStroke() {
        val path = currentStrokePath
        if (path != null && strokeCount > 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            val duration = (now - currentStrokeStartTime).coerceIn(1L, CONTINUOUS_STROKE_DURATION_MS)
            val stroke = GestureDescription.StrokeDescription(path, 0, duration, false)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
        currentStrokePath = null
        currentStrokeStartTime = 0L
        strokeCount = 0
    }

    // ── Navigation actions ────────────────────────────────────────────────────

    fun goBack() = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun goHome() = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun goRecents() = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }
    fun openNotifications() = runCatching { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }

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
        // Detect secure window (lock screen, PIN entry, etc.) — injection will fail silently.
        val root = rootInActiveWindow
        if (root == null) {
            BridgeLogger.w(TAG, "Secure or inaccessible window — key injection blocked (keyCode=$keyCode)")
            DiagnosticsManager.update { copy(isSecureWindow = true) }
            return
        }
        root.recycle()
        DiagnosticsManager.update { copy(isSecureWindow = false) }

        try {
            injectKeyCodeInternal(keyCode, modifiers)
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "injectKeyCode failed (keyCode=$keyCode)", e)
            DiagnosticsManager.update { copy(lastInjectionError = "Key inject: ${e.message}") }
        }
    }

    private fun injectKeyCodeInternal(keyCode: Int, modifiers: ModifierState) {
        // BUG-XXX FIX: Ctrl+Arrow keys (word-by-word cursor movement) were silently
        // dropped because handleCtrlKey() only handles A/C/V/X and returns immediately.
        // Route arrow keys to handleArrowKey with word granularity when Ctrl is held.
        if (modifiers.ctrl) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    handleArrowKey(
                        forward = false,
                        granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                        extendSelection = modifiers.shift,
                    )
                    return
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    handleArrowKey(
                        forward = true,
                        granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                        extendSelection = modifiers.shift,
                    )
                    return
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    handleArrowKey(
                        forward = false,
                        granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                        extendSelection = modifiers.shift,
                    )
                    return
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    handleArrowKey(
                        forward = true,
                        granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                        extendSelection = modifiers.shift,
                    )
                    return
                }
                else -> {
                    handleCtrlKey(keyCode)
                    return
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                // Backspace: remove character before cursor (or delete selection)
                val focused = getFocused() ?: return
                try {
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
                } finally {
                    focused.recycle()
                }
            }

            KeyEvent.KEYCODE_FORWARD_DEL -> {
                // Delete key: remove character after cursor
                val focused = getFocused() ?: return
                try {
                    val text = focused.text?.toString() ?: return
                    val selStart = focused.textSelectionStart.coerceIn(0, text.length)
                    val selEnd = focused.textSelectionEnd.coerceIn(selStart, text.length)
                    val newText = if (selStart < selEnd) {
                        text.substring(0, selStart) + text.substring(selEnd)
                    } else if (selStart < text.length) {
                        text.substring(0, selStart) + text.substring(selStart + 1)
                    } else return
                    setFocusedText(focused, newText)
                } finally {
                    focused.recycle()
                }
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val focused = getFocused()
                if (focused != null) {
                    try {
                        if (focused.isEditable) {
                            // Try to perform IME action (submit/go/search), fall back to newline
                            val didClick = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (!didClick) {
                                injectCharacterIntoFocused(focused, '\n')
                            }
                        } else {
                            // No focused text field — act as a global click/confirm
                            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    } finally {
                        focused.recycle()
                    }
                }
            }

            KeyEvent.KEYCODE_TAB -> {
                // Move focus to next element
                val root = rootInActiveWindow
                root?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                root?.recycle()
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

            // F1-F12 keys — try performAction on focused node, fallback to global action
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                val focused = getFocused()
                if (focused != null) {
                    try {
                        // Try clicking the focused node (many apps handle F-keys via click)
                        focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        focused.recycle()
                    }
                }
            }

            // Numpad keys — map to their base equivalents
            KeyEvent.KEYCODE_NUMPAD_0 -> injectPrintableChar(modifiers, '0')
            KeyEvent.KEYCODE_NUMPAD_1 -> injectPrintableChar(modifiers, '1')
            KeyEvent.KEYCODE_NUMPAD_2 -> injectPrintableChar(modifiers, '2')
            KeyEvent.KEYCODE_NUMPAD_3 -> injectPrintableChar(modifiers, '3')
            KeyEvent.KEYCODE_NUMPAD_4 -> injectPrintableChar(modifiers, '4')
            KeyEvent.KEYCODE_NUMPAD_5 -> injectPrintableChar(modifiers, '5')
            KeyEvent.KEYCODE_NUMPAD_6 -> injectPrintableChar(modifiers, '6')
            KeyEvent.KEYCODE_NUMPAD_7 -> injectPrintableChar(modifiers, '7')
            KeyEvent.KEYCODE_NUMPAD_8 -> injectPrintableChar(modifiers, '8')
            KeyEvent.KEYCODE_NUMPAD_9 -> injectPrintableChar(modifiers, '9')
            KeyEvent.KEYCODE_NUMPAD_DOT -> injectPrintableChar(modifiers, '.')
            KeyEvent.KEYCODE_NUMPAD_ADD -> injectPrintableChar(modifiers, '+')
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> injectPrintableChar(modifiers, '-')
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> injectPrintableChar(modifiers, '*')
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> injectPrintableChar(modifiers, '/')

            // Media keys — BUG-XXX FIX: the old code mapped these to unrelated global actions
            // (PLAY_PAUSE→BACK, STOP→HOME, NEXT→RECENTS, PREVIOUS→NOTIFICATIONS) which was
            // semantically wrong. Accessibility services cannot inject media key events directly;
            // log and drop so the user gets correct feedback instead of random actions.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                BridgeLogger.d(TAG, "Media key ${KeyEvent.keyCodeToString(keyCode)} — " +
                    "no accessibility equivalent, dropped")
            }

            else -> {
                // Attempt to resolve a printable Unicode character from the keyCode
                val metaState = buildMetaState(modifiers)
                val kev = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
                val unicode = kev.unicodeChar
                if (unicode != 0) {
                    val focused = getFocused()
                    if (focused != null) {
                        try {
                            injectCharacterIntoFocused(focused, unicode.toChar())
                        } finally {
                            focused.recycle()
                        }
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
     * Helper to inject a printable character with comprehensive fallback chain:
     * 1. ACTION_SET_TEXT (primary)
     * 2. Clipboard paste (fallback)
     * 3. performAction (last resort)
     */
    private fun injectPrintableChar(modifiers: ModifierState, ch: Char) {
        val effectiveChar = if (modifiers.shift || modifiers.capsLock) ch.uppercaseChar() else ch
        val focused = getFocused()
        if (focused != null) {
            try {
                // Strategy 1: Try ACTION_SET_TEXT
                val currentText = focused.text?.toString() ?: ""
                val selStart = focused.textSelectionStart.coerceIn(0, currentText.length)
                val newText = currentText.substring(0, selStart) + effectiveChar + currentText.substring(selStart)
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                val didSetText = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (didSetText) return

                // Strategy 2: Clipboard paste
                // BUG-XXX FIX: save and restore clipboard to avoid permanently
                // overwriting the user's clipboard content as a side effect.
                try {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val oldClip = clip.primaryClip
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("InputBridge", effectiveChar.toString()))
                    focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    // Restore old clipboard after paste completes
                    if (oldClip != null) {
                        clip.setPrimaryClip(oldClip)
                    }
                    return
                } catch (e: Exception) {
                    BridgeLogger.d(TAG, "Clipboard paste failed for char '$effectiveChar'")
                }

                // Strategy 3: performAction (last resort)
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                focused.recycle()
            }
        }
    }

    /**
     * Inject a complete text string into the focused text field.
     * Uses ACTION_SET_TEXT (append at cursor) as the primary strategy.
     * Falls back to clipboard paste if the node does not support ACTION_SET_TEXT.
     */
    fun injectText(text: String) {
        val root = rootInActiveWindow
        if (root == null) {
            BridgeLogger.w(TAG, "Secure or inaccessible window — text injection blocked")
            DiagnosticsManager.update { copy(isSecureWindow = true) }
            return
        }
        root.recycle()
        DiagnosticsManager.update { copy(isSecureWindow = false) }
        try {
            injectTextInternal(text)
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "injectText failed", e)
            DiagnosticsManager.update { copy(lastInjectionError = "Text inject: ${e.message}") }
        }
    }

    private fun injectTextInternal(text: String) {
        // Comprehensive fallback chain for text injection:
        // 1. ACTION_SET_TEXT on focused node
        // 2. Clipboard paste
        // 3. Character-by-character injection via commitText

        val focused = getFocused()
        if (focused != null) {
            try {
                // Strategy 1: Try ACTION_SET_TEXT (most reliable for standard EditText)
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

                // Strategy 2: Clipboard paste (works on many non-standard text fields)
                // BUG-XXX FIX: save and restore clipboard to avoid permanently
                // overwriting the user's clipboard content as a side effect.
                try {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    val oldClip = clip.primaryClip
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("InputBridge", text))
                    val didPaste = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    if (oldClip != null) {
                        clip.setPrimaryClip(oldClip)
                    }
                    if (didPaste) return
                } catch (e: Exception) {
                    BridgeLogger.d(TAG, "Clipboard paste failed: ${e.message}")
                }

                // Strategy 3: Character-by-character injection
                // Some apps don't support ACTION_SET_TEXT — inject one char at a time
                try {
                    for (ch in text) {
                        focused.refresh()
                        val charCurrent = focused.text?.toString() ?: ""
                        val charSelStart = focused.textSelectionStart.coerceIn(0, charCurrent.length)
                        val charNewText = charCurrent.substring(0, charSelStart) + ch + charCurrent.substring(charSelStart)
                        val charBundle = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, charNewText)
                        }
                        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, charBundle)
                    }
                    BridgeLogger.d(TAG, "Character-by-character injection succeeded for ${text.length} chars")
                    return
                } catch (e: Exception) {
                    BridgeLogger.d(TAG, "Character-by-character injection failed: ${e.message}")
                }
            } finally {
                focused.recycle()
            }
        }

        // Final fallback: try to find any editable node and inject there
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val editableNodes = mutableListOf<AccessibilityNodeInfo>()
                findEditableNodes(root, editableNodes)
                for (node in editableNodes) {
                    try {
                        val current = node.text?.toString() ?: ""
                        val bundle = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                current + text
                            )
                        }
                        val didSet = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                        if (didSet) {
                            BridgeLogger.d(TAG, "Text injected via fallback editable node")
                            return
                        }
                    } catch (e: Exception) {
                        // Continue to next node
                    } finally {
                        // BUG-XXX FIX: recycle the node now that we are done with it.
                        node.recycle()
                    }
                }
            } finally {
                // BUG-112 FIX (audit B): recycle the tree root obtained above.
                root.recycle()
            }
        }

        BridgeLogger.w(TAG, "All text injection strategies failed")
    }

    /**
     * Recursively find all editable nodes in the accessibility tree.
     * BUG-XXX FIX: recycle intermediate (non-editable) child nodes obtained via
     * getChild() to avoid pool exhaustion under sustained input. Editable nodes are
     * kept in [result] and MUST NOT be recycled here — the caller in injectTextInternal
     * recycles them after use. Recycling a node that is still referenced and later used
     * throws IllegalStateException: Cannot perform this action on a recycled node.
     */
    private fun findEditableNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childIsEditable = child.isEditable
            findEditableNodes(child, result)
            // Only recycle intermediate nodes that are NOT held in result.
            if (!childIsEditable) child.recycle()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Get the currently input-focused accessibility node, or null.
     * BUG-112 FIX (audit B): the root node obtained via rootInActiveWindow is an owned
     * instance that must be recycled. Recycle it here so callers only own `focused`.
     */
    private fun getFocused(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        return focused
    }

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
        if (focused != null) {
            try {
                when (keyCode) {
                    KeyEvent.KEYCODE_A -> {
                        // Select all: use ACTION_SET_SELECTION spanning the full text range.
                        // ACTION_SELECT_ALL does not exist in the Android SDK — the correct
                        // approach is to set the selection from 0 to text.length.
                        val node = focused
                        val text = node.text?.toString() ?: ""
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                    }
                    KeyEvent.KEYCODE_C -> focused.performAction(AccessibilityNodeInfo.ACTION_COPY)
                    KeyEvent.KEYCODE_V -> focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    KeyEvent.KEYCODE_X -> focused.performAction(AccessibilityNodeInfo.ACTION_CUT)
                    else -> BridgeLogger.d(TAG, "Ctrl+${KeyEvent.keyCodeToString(keyCode)} not handled")
                }
            } finally {
                focused.recycle()
            }
        }
    }

    /** Move the cursor in the focused node by the given granularity. */
    private fun handleArrowKey(
        forward: Boolean,
        granularity: Int,
        extendSelection: Boolean,
    ) {
        val focused = getFocused() ?: return
        try {
            val action = if (forward)
                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            else
                AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extendSelection)
            }
            focused.performAction(action, args)
        } finally {
            focused.recycle()
        }
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
