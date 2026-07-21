---
name: BUG-046 through BUG-053 audit (Session 014)
description: Full deep-audit findings, analysis, and fix decisions for the 8 new bugs discovered in Session 014 after all Phase 1–7 work was complete.
---

# InputBridge — Session 014 Deep Audit

**Date:** 2026-07-21  
**Audited by:** Claude (Replit Agent)  
**Scope:** All 25+ source files across all 9 modules, both apps, all services, viewmodels, and CI yml

---

## Methodology

Read every significant `.kt` file top-to-bottom, cross-referencing the sealed class hierarchy,
atomic/flow usage, USB lifecycle, BT HID tables, and protocol IDs. Checked every TODO, every
`?.` null-safe call, every `when` branch for exhaustiveness, every AtomicLong read-modify-write,
and every resource release path (USB, socket, WakeLock).

---

## Confirmed Bugs (all documented in BUGS.md)

### BUG-046 — Dead `else` in sealed `when`
- **File:** `accessibility-receiver/.../AccessibilityCommandBus.kt` line 220
- **Root cause:** `when (event)` over sealed `InputEvent` lists all 9 subtypes explicitly, then
  adds `else ->`. The `else` is unreachable and suppresses Kotlin's compile-time exhaustiveness
  check. Future subtypes will silently go unhandled.
- **Fix:** Delete the `else` branch. Compiler now enforces exhaustiveness.

### BUG-047 — Empty IP in notification after silence recovery
- **File:** `app-receiver/.../ReceiverService.kt` line 329
- **Root cause:** PING handler calls `updateNotification("Paired with bridge ($pairedBridgeIp)")`
  when clearing `bridgeSilenceNotified`. In open-mode sessions (no PIN), `pairedBridgeIp` is
  `""` because no `PAIR_REQUEST`/`PAIR_RESPONSE` exchange ever happened. Result: notification
  text `"Paired with bridge ()"`.
- **Fix:** Ternary guard — use `"Paired with bridge ($pairedBridgeIp)"` only when non-empty,
  else `"Bridge reconnected — PIN: $sessionPin"`. `sessionPin` is in scope (local val in
  `startListening()`).

### BUG-048 — USB interfaces not released before close
- **File:** `input-capture/.../UsbInputCapture.kt` `stop()` lines 114–123
- **Root cause:** `claimInterface()` is called in `start()` but there is no corresponding
  `releaseInterface()` in `stop()`. USB spec and some Android drivers expect explicit release
  before close; skipping it can leave the interface locked until kernel timeout.
- **Fix:** Track each successfully claimed interface in `claimedInterfaces: MutableList<UsbInterface>`.
  In `stop()`, iterate and call `conn.releaseInterface(iface)` for each before `conn.close()`.
  Add claim tracking right after `claimInterface()` succeeds (before endpoint check — even if
  no endpoint is found, we still claimed the interface and must release it).

### BUG-049 — Stale `lastCaptureToSendUs` after reconnect
- **File:** `app-bridge/.../BridgeService.kt` `triggerReconnect()` lines 480–481
- **Root cause:** `triggerReconnect()` resets `lastPingSentAtMs` and `lastPongReceivedMs` but
  not `lastCaptureToSendUs`. The counter-flush job runs every 1 s and reads this AtomicLong.
  It will report the last pre-reconnect latency sample until a new input event writes a fresh
  value.
- **Fix:** Add `lastCaptureToSendUs.set(0L)` immediately after the other reset lines.

### BUG-050 — `ANDROID_TO_HID` missing MENU + F13–F24 (partial BUG-038 fix)
- **File:** `transport-bluetooth-hid/.../HidReportBuilder.kt`
- **Root cause:** BUG-038 added 13 new keys to `KeyMap.HID_TO_ANDROID` (the USB→Android decode
  table). The inverse `HidReportBuilder.ANDROID_TO_HID` (Android→HID re-encode table for BT HID
  output) was NOT updated. Only the UDP/accessibility path benefits from BUG-038; BT HID mode
  still silently drops these keys.
- **Confirmed missing (vs HID Usage Tables 1.5):**
  - 0x65 = Application (KEYCODE_MENU)
  - 0x68–0x73 = F13–F24
- **Already present (not a problem):** SYSRQ (0x46), SCROLL_LOCK (0x47), BREAK (0x48),
  INSERT (0x49), NUM_LOCK (0x53), all numpad keys (0x54–0x63)
- **Fix:** Append 13 entries to `ANDROID_TO_HID` map.

### BUG-051 — `WIFI_DIRECT_ENABLED = true` with stub transport
- **File:** `shared-core/.../FeatureFlags.kt` line 23
- **Root cause:** Flag was never changed to `false` after Phase 7 when the WelcomeScreen was
  fixed to hide the stub mode. Any code reading this flag to enable the transport would activate
  the unimplemented stub.
- **Fix:** `false`. Re-enable when `WifiDirectTransport` is implemented.

### BUG-052 — `numLock` always false (WONTFIX)
- **File:** `shared-core/.../InputEvent.kt` `ModifierState`
- **Root cause:** USB HID Input reports (device→host, boot protocol) carry modifier byte for
  Ctrl/Shift/Alt/GUI only. Lock-key state (CapsLock/NumLock/ScrollLock) is reported via LED
  Output reports (host→device). The bridge only processes Input reports; it never reads Output
  reports. So `numLock` is architecturally always false.
- **Decision:** WONTFIX. Removing the field would be a wire-protocol change (bit 0x20) requiring
  a `PROTOCOL_VERSION` bump and paired APK update. The field is inert and doesn't affect
  correctness (numpad digits work via normal keycode path regardless of numLock state).

### BUG-053 — `DiagnosticsManager.update {}` race condition
- **File:** `diagnostics/.../DiagnosticsManager.kt` line 26
- **Root cause:** `_state.value = _state.value.block()` is a non-atomic read-modify-write.
  `MutableStateFlow.value` setter is thread-safe individually, but the read+compute+set
  triple is not. Three concurrent callers exist: `counterFlushJob` (IO, every 1 s),
  `captureJob` (IO, every USB event ≈125 Hz), `watchdogJob` (IO, every 3–5 s). Under load,
  updates can overwrite each other, dropping diagnostic counter increments.
- **Fix:** `private val updateLock = Any()` + `synchronized(updateLock) { ... }` around the
  read-modify-write. The UI collector on Main is not blocked (it reads `state.value` via
  `StateFlow.collect` not via the lock).

---

## Bugs Investigated and Cleared (not real bugs)

- `BridgeService.onDestroy()` `udpTransport?.send(...)` with `?.` — null-safe, no bug.
- `ReceiverService.handleArrowKey` null check — `getFocused()` null guard is inside the function,
  handles it correctly.
- `BridgeService.triggerReconnect()` `lastCaptureToSendUs` not reset — confirmed real (BUG-049).
- `AccessibilityCommandBus.handleEvent` `else ->` — confirmed dead code (BUG-046).

---

## Key Architectural Observations (for future sessions)

**Why:** Preserved here because these are non-obvious constraints that future code must respect.

1. **`InputEvent.sealed` class exhaustiveness** — always use `when (event)` without `else` so
   the compiler enforces coverage. Adding a new subtype without updating all `when` blocks is
   a hard compile error, not a silent runtime drop.

2. **BT HID `ANDROID_TO_HID` must be kept in sync with `KeyMap.HID_TO_ANDROID`** — these are
   inverses of each other. Any key added to `KeyMap` for USB→accessibility path must also be
   added to `HidReportBuilder` for the BT HID path. There is no automated check for this.

3. **`DiagnosticsManager.update {}` is a synchronized critical section** — any caller on a
   non-Main thread must go through `update {}`, never directly mutate `_state.value`.

4. **USB `releaseInterface()` must precede `close()`** — Android USB host API contract.
   Always track claimed interfaces in a list in `start()` and release all in `stop()`.

5. **`FeatureFlags.WIFI_DIRECT_ENABLED` must stay `false`** until `WifiDirectTransport` is
   fully implemented and tested. Re-enabling it prematurely will activate an unimplemented code
   path.

6. **`pairedBridgeIp` can be empty in open-mode sessions** — any code that formats a
   user-visible string using `pairedBridgeIp` must guard for the empty case.
