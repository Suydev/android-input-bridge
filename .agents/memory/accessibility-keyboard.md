---
name: Accessibility keyboard injection
description: How to inject keystrokes via Android Accessibility API — the patterns that work
---

# Accessibility keyboard injection

**Why:** Accessibility API is the only non-root path for input injection. Several subtle issues exist.

**How to apply:** Use these patterns in InputBridgeAccessibilityService whenever keyboard injection is needed.

## Character resolution
```kotlin
val metaState = buildMetaState(modifiers)  // see below
val kev = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
val unicode = kev.unicodeChar  // 0 if not printable
```
`buildMetaState`: `META_SHIFT_ON | META_SHIFT_LEFT_ON` for shift/capsLock, `META_CTRL_ON | META_CTRL_LEFT_ON` for ctrl, etc. Include both left and generic flags.

## Text insertion (selection-aware)
```kotlin
val text = focused.text?.toString() ?: ""
val selStart = focused.textSelectionStart.coerceIn(0, text.length)  // -1 if no selection
val selEnd   = focused.textSelectionEnd.coerceIn(selStart, text.length)
val newText  = text.substring(0, selStart) + insertionText + text.substring(selEnd)
val bundle = Bundle().apply {
    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
}
focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
```
**Critical:** `textSelectionStart` returns -1 when there's no selection — always `coerceIn(0, text.length)`.

## Arrow key movement
```kotlin
focused.performAction(
    AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
    Bundle().apply {
        putInt(ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, MOVEMENT_GRANULARITY_CHARACTER)
        putBoolean(ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, modifiers.shift)
    }
)
```
Ctrl+Arrow → WORD granularity. Up/Down → LINE granularity.

## Ctrl shortcuts
- `ACTION_SELECT_ALL`, `ACTION_COPY`, `ACTION_PASTE`, `ACTION_CUT` — all work on editable focused nodes

## Getting the focused node
```kotlin
rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
```
Returns null if no editable field is focused — handle gracefully.
