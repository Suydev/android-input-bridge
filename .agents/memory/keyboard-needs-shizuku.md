---
name: Keyboard injection needs Shizuku not Accessibility
description: On the receiver, real key events require Shizuku/InputManager; AccessibilityService can only drive a focused EditText
---

# Keyboard Injection Requires Shizuku

**Rule:** On the InputBridge receiver, real system key events can ONLY be injected via
Shizuku (`InputManager.injectInputEvent`, shell uid 2000). The `AccessibilityService` path
(`dispatchGesture`) handles taps/swipes/scroll but CANNOT inject keycodes — `injectKeyCode`
only manipulates a focused `EditText`. So keyboard input is dead unless Shizuku is installed,
running, AND its runtime permission is granted.
**Why:** An AccessibilityService has no API to inject arbitrary `KeyEvent`s system-wide.
`ShizukuInputInjector` was only `checkSelfPermission()`-ing, never `requestPermission()`-ing
(BUG-132) — so it always fell back and keyboard never worked.
**How to apply:** If a user reports "keyboard doesn't work but mouse does", check Shizuku
binder + permission first. The permissions screen must offer the grant. Mouse/trackpad need
no Shizuku.
