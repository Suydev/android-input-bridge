# Session Log

---

## Session 001 — Phase 1 Scaffold
**Date:** 2025-07-19
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Design the full 9-module architecture
- Write all source files for every module
- Write all documentation (13 files)
- Set up GitHub Actions CI
- Establish coding conventions and AI continuity rules

### What Was Done
- Created all 9 modules: shared-core, protocol, input-capture, transport-wifi,
  transport-bluetooth-hid (stub), accessibility-receiver, diagnostics,
  app-bridge, app-receiver
- Created build-logic with 3 convention plugins (app, library, compose)
- Wrote full binary UDP protocol with frozen packet type IDs
- Wrote USB HID input capture (keyboard + mouse boot protocol)
- Wrote both full Compose UIs (terminal aesthetic, black bg, green/blue accents)
- Wrote 22 unit tests across shared-core and protocol modules
- Wrote 13 documentation files (README, AI_CONTEXT, PROTOCOL, DECISIONS, etc.)
- Set up .github/workflows/ci.yml for debug APK + unit test CI

### Key Decisions Made This Session
- DEC-001: Binary UDP protocol (not JSON/TCP)
- DEC-002: UDP transport (fire-and-forget, lowest latency)
- DEC-003: PacketType IDs frozen (break pairing if changed)
- DEC-004: Accessibility Service for Path B (no real cursor)
- DEC-005: Bluetooth HID planned for Phase 6 (real cursor)
- DEC-006: Koin for DI (lightweight vs. Hilt)
- DEC-007: DiagnosticsManager as singleton

### Files Created
99 files total — see MODULES.md and CHANGELOG.md for full list.

---

## Session 002 — Git Push + Build System Fixes
**Date:** 2025-07-19
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Fix build-logic dependency declarations
- Fix module build.gradle.kts (remove catalog aliases for convention plugins → use id() directly)
- Minimal AndroidManifest.xml for all library modules
- Add BluetoothHidTransport stub source file
- Add proguard-rules.pro for both app modules
- Add gradlew script + gradle-wrapper.jar
- Git init + push to GitHub

### What Was Done
- Fixed `build-logic/build.gradle.kts` to use direct Maven coordinates
  (not version catalog aliases, which aren't accessible from build-logic)
- Fixed all 9 module build.gradle.kts to use `id("inputbridge.android.*")`
  instead of `alias(libs.plugins.inputbridge.*)` (convention plugins are
  local, not Maven artifacts)
- Removed inputbridge.* aliases from libs.versions.toml (they're local-only)
- Added minimal AndroidManifest.xml to all 7 library modules
- Added `BluetoothHidTransport.kt` stub (Phase 6 placeholder)
- Added `proguard-rules.pro` to app-bridge and app-receiver
- Created `gradlew` shell script (POSIX-compliant, standard Gradle wrapper)
- Created `gradlew.bat` for Windows
- Downloaded real `gradle-wrapper.jar` (43 KB, Gradle 8.7)
- Created GitHub repo `Suydev/android-input-bridge` (was empty, already existed)
- Committed 99 files (6,584 insertions) and pushed to `main`
- GitHub Actions CI triggered on push

### Lessons
- build-logic cannot access the version catalog via `libs.*` in its
  own build.gradle.kts — use direct Maven coordinates instead.
- Convention plugins (build-logic) must be referenced as `id("inputbridge.*")`
  in modules; they are not aliases in the version catalog.
- git push with embedded PAT in URL can fail if the URL string gets mangled;
  use `url.https://<PAT>@github.com/.insteadOf` git config instead.

---

## Session 003 — Phase 2: USB Input Wiring
**Date:** 2026-07-19
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Wire UsbInputCapture into BridgeService (real USB HID events flow)
- Wire UdpTransport into ReceiverService (real packet receive + accessibility dispatch)
- Persist transport config (target IP / port) across service restarts
- Update DiagnosticsManager counters from both services
- Add Packet → InputEvent conversion on the receiver side

### What Was Done

**New files:**
- `protocol/PacketToEventConverter.kt` — stateless Packet→InputEvent converter
  using PacketSerializer parsers; returns null for control packets
- `app-bridge/prefs/BridgePreferences.kt` — SharedPreferences wrapper for
  target IP and port (persisted across service restarts; DataStore in Phase 7)
- `app-receiver/prefs/ReceiverPreferences.kt` — SharedPreferences wrapper
  for listen port

**Modified files:**
- `app-bridge/service/BridgeService.kt` — Phase 2 full pipeline:
  - Dynamic BroadcastReceiver for USB_DEVICE_ATTACHED, USB_DEVICE_DETACHED,
    and ACTION_USB_PERMISSION
  - `requestUsbPermission(device)` → PendingIntent broadcast flow
  - `startCapture(device)` → UsbInputCapture.start() + collect events Flow
  - Hot path: InputEvent → EventPacketFactory.fromEvent() → UdpTransport.send()
  - DiagnosticsManager.onPacketSent() / onSendFailed() per packet
  - 1s periodic counter flush loop
  - Checks for pre-attached HID devices on service start
  - API 33+ RECEIVER_NOT_EXPORTED flag for dynamic receiver registration
- `app-receiver/service/ReceiverService.kt` — Phase 2 full listener:
  - UdpTransport in receive mode (binds to configurable port, default 54321)
  - Collects incomingPackets → PacketToEventConverter → AccessibilityCommandBus.post()
  - DiagnosticsManager.onPacketReceived() per packet
  - 1s periodic counter flush loop
  - WakeLock added (was missing in Phase 1 stub)
- `app-bridge/di/BridgeModule.kt` — added BridgePreferences singleton
- `app-bridge/viewmodel/BridgeViewModel.kt` — injected BridgePreferences;
  setTargetIp() and setPort() now persist to SharedPreferences; config loaded
  from prefs on init
- `app-receiver/di/ReceiverModule.kt` — added ReceiverPreferences singleton
- `app-receiver/viewmodel/ReceiverViewModel.kt` — injected ReceiverPreferences;
  setListenPort() now persists to SharedPreferences; port loaded from prefs on init

### Service Lifecycle Fixes (code review feedback)
- **Teardown ordering** — `onDestroy()` now cancels individual jobs first, then
  cleans up USB/socket resources in `withContext(NonCancellable + Dispatchers.IO)`,
  then cancels `serviceScope`. Previously the scope was cancelled before cleanup
  coroutines could complete, leaving sockets / USB connections open.
- **Idempotent start** — `onStartCommand()` uses `AtomicBoolean.compareAndSet(false, true)`
  _before_ launching the startup coroutine, so rapid repeated starts (e.g. BootReceiver
  + user tap overlap) cannot create duplicate pipelines. The flag is reset in
  `onDestroy()` and on failed startup paths, allowing clean retries.

### Key Decisions Made This Session
- SharedPreferences (not DataStore) for Phase 2 config persistence — DataStore
  in Phase 7 when all settings are fully wired. Simple and zero extra dependencies.
- Packet → InputEvent conversion lives in the `protocol` module as
  `PacketToEventConverter` (not inline in ReceiverService) — keeps the
  module boundary clean and allows unit testing.
- Dynamic USB receiver registered in the service (not manifest) — lets the
  service own the full USB lifecycle without requiring the Activity to relay
  intents.

### Entry point for Phase 3
`app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`
`app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt`

---

## Session 004 — CI Build Fixes
**Date:** 2026-07-19
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Diagnose and fix all GitHub Actions CI build failures
- Get both APKs building successfully on CI (run #25)

### CI Failures Found and Fixed (in order)

**Run #22 — 3 Kotlin compiler errors:**
1. `DiagnosticsManager.flushCounters()` — inside the `DiagnosticsData.() -> DiagnosticsData`
   lambda, `packetsSent` resolved to `DiagnosticsData.packetsSent` (a `Long`) instead of the
   outer `AtomicLong`, causing `Unresolved reference 'get'`. Fix: capture atomic values as
   local `val` before entering the lambda.
2. `InputBridgeAccessibilityService` — two `companion object` blocks in one class (Kotlin
   only allows one). `TAP_DURATION_MS` was in the second block; merged into the first.
3. `UsbInputCapture` — `isActive(coroutineContext)` is not a valid API. Fixed to
   `this@UsbInputCapture.isActive && coroutineContext.isActive` (class field + coroutine
   extension property) in all 3 loop guards.

**Run #23 — AAPT resource error:**
4. `app-receiver/res/values/themes.xml` used `android:Theme.Material.NoTitleBar.Fullscreen`
   which is not resolvable via AAPT in this SDK setup. Fixed: use
   `Theme.Material3.Dark.NoActionBar` (same as app-bridge, provided by the
   `androidx.compose.material3` dependency already declared in app-receiver).

**Run #24 — 4 more Kotlin compiler errors:**
5. `BuildConfig` unresolved in BridgeApplication, ReceiverApplication, AboutScreen —
   `buildFeatures { buildConfig = true }` was missing from `AndroidAppConventionPlugin.kt`.
   Added it.
6. `androidContext()` unresolved in BridgeModule and ReceiverModule — the
   `org.koin.android.ext.koin.androidContext` import was missing. Added to both module files.
7. `android.R.drawable.ic_menu_receive` does not exist in the Android SDK.
   Fixed: use `android.R.drawable.ic_menu_send` (exists, used by app-bridge too).

**Run #25 — ✅ SUCCESS**
- Both `app-bridge` and `app-receiver` debug APKs built successfully
- Unit tests (shared-core, protocol) continue to pass
- APK artifacts uploaded to GitHub Actions

### Commits in This Session
- `774ba97` — Fix 3 compile errors (DiagnosticsManager, InputBridgeAccessibilityService, UsbInputCapture)
- `8dbec88` — Fix app-receiver theme parent (AAPT resource error)
- `9931cb8` — Fix 4 more compile errors (BuildConfig, androidContext, ic_menu_receive)

### Lessons
- `buildFeatures { buildConfig = true }` must be explicit in convention plugins for AGP 8.x —
  it is no longer generated by default.
- Inside a Koin `module { }` lambda, the `androidContext()` shorthand requires
  `import org.koin.android.ext.koin.androidContext` — it is not auto-imported.
- Inside a `DiagnosticsData.() -> DiagnosticsData` lambda, outer-scope names that conflict
  with data class fields must be captured as locals before the lambda or referenced via
  qualified `this@ObjectName`.
- `android.R.drawable.ic_menu_receive` does not exist — use `ic_menu_send` or a
  valid `stat_sys_*` drawable instead.

---

## Session 005 — Phase 3+4: Keep-alive + Full Accessibility Injection
**Date:** 2026-07-21
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Fix all "dummy" behavior — app was not responding to real input
- Implement PING/PONG keep-alive with latency measurement
- Implement full keyboard injection via accessibility
- Fix accessibility service status detection
- Wire mouse sensitivity setting end-to-end
- Push to GitHub and monitor CI

### Root Causes Found (all dummy behavior)
1. `accessibilityEnabled` in DiagnosticsManager was never set to `true` — service never
   called DiagnosticsManager on connect/unbind
2. Screen size hardcoded at 1080×2400 — `setScreenSize()` never called with real dims
3. All `KeyDown`/`KeyUp` events dropped silently — `/* Phase 4 */` stub comment only
4. `TextInput` events logged but not injected — `injectText()` didn't exist
5. PING packets sent by bridge were dropped on receiver — PacketToEventConverter returned
   null for control packets and ReceiverService didn't check type before converting
6. UdpTransport in receiver mode could not send — `startSendLoop` tried to resolve empty
   `config.targetIp`, would throw or fail silently
7. Sensitivity slider was `onValueChange = { /* Phase 7 */ }` — pure no-op

### What Was Fixed / Implemented

**UdpTransport** (`transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`):
- Added `@Volatile private var lastSenderAddress: InetSocketAddress?`
- `startReceiveLoop()`: captures `dp.socketAddress as InetSocketAddress` after each receive
- `startSendLoop()`: split by mode — sender uses config.targetIp, receiver uses lastSenderAddress
- This enables PONG replies from the receiver back to the bridge

**InputBridgeAccessibilityService** (`accessibility-receiver/.../InputBridgeAccessibilityService.kt`):
- `onServiceConnected()`: fetches real screen size via WindowManager (API 30+) /
  DisplayMetrics (API 29), calls `AccessibilityCommandBus.setScreenSize()`, updates
  DiagnosticsManager (`accessibilityEnabled = true, accessibilityMode = "Accessibility"`)
- `onUnbind()`: clears DiagnosticsManager
- `injectKeyCode(keyCode, modifiers)`: full keyboard injection:
  - Printable chars: `KeyEvent.unicodeChar` with `buildMetaState(modifiers)`
  - Backspace/Forward-delete: `ACTION_SET_TEXT` removing char at cursor (selection-aware)
  - Enter: `ACTION_CLICK` on focused node (form submit), fallback to newline injection
  - Tab: focus next element
  - Escape: `GLOBAL_ACTION_BACK`
  - Arrow keys: `ACTION_NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY` (char or word with Ctrl)
  - Home/End: line granularity movement
  - Ctrl+A/C/V/X: `ACTION_SELECT_ALL/COPY/PASTE/CUT`
- `injectText(text)`: `ACTION_SET_TEXT` at cursor, clipboard paste fallback
- All text operations are selection-aware via `textSelectionStart/End`

**AccessibilityCommandBus** (`accessibility-receiver/.../AccessibilityCommandBus.kt`):
- `KeyDown` → `svc.injectKeyCode(event.keyCode, event.modifiers)`
- `KeyUp` → no-op (injection complete on down)
- `TextInput` → `svc.injectText(event.text)`
- `Scroll` → swipe gesture with `SCROLL_PIXEL_MULTIPLIER * sensitivity` scaling
- `mouseSensitivity` multiplier applied to all `MouseMove` dx/dy
- `setSensitivity(Float)` API, clamped to 0.1–10
- `setScreenSize()` re-centres cursor

**BridgeService** (`app-bridge/.../service/BridgeService.kt`):
- `pingJob`: sends PING every 1 s once transport connected, records `lastPingSentAtMs`
- `pongResponseJob`: collects `incomingPackets`, on PONG computes `now - lastPingSentAtMs`,
  calls `DiagnosticsManager.recordLatency()` (sanity check: 0–10000 ms)
- Both jobs cancelled in `onDestroy()`

**ReceiverService** (`app-receiver/.../service/ReceiverService.kt`):
- Added `EventPacketFactory packetFactory`
- Receive loop checks `packet.type` before `PacketToEventConverter`:
  - `PING` → `transport.send(packetFactory.makePong(seq))`
  - `KEEP_ALIVE` → log
  - `DISCONNECT` → update DiagnosticsManager + notification
  - everything else → PacketToEventConverter → AccessibilityCommandBus
- Applies persisted sensitivity on startup: `AccessibilityCommandBus.setSensitivity(prefs.mouseSensitivity)`

**ReceiverPreferences** (`app-receiver/.../prefs/ReceiverPreferences.kt`):
- Added `mouseSensitivity: Float` (KEY = `mouse_sensitivity`, default = 1.0f)

**ReceiverViewModel** (`app-receiver/.../viewmodel/ReceiverViewModel.kt`):
- `setMouseSensitivity(Float)`: clamps 0.1–5.0, persists to prefs, applies immediately
  to AccessibilityCommandBus if service running (API 24+)
- Config pre-loaded with port + sensitivity from prefs

**ReceiverSettingsScreen** (`app-receiver/.../ui/screens/ReceiverSettingsScreen.kt`):
- Sensitivity slider fully wired to `viewModel.setMouseSensitivity()`
- Steps = 48 (0.1 resolution across 0.1–5.0 range)
- Live label: "Pointer Sensitivity: X.X×"
- Section headers and dividers added

### Commit
- `2bc466f` — Phase 3+4: PING/PONG keep-alive, full keyboard injection, accessibility
  detection, mouse sensitivity

### Key Decisions Made This Session
- **Keyboard injection on KeyDown only** — accessibility `ACTION_SET_TEXT` is stateful
  (replaces text at cursor) so a separate KeyUp has no meaningful counterpart.
  KeyUp events are no-ops in AccessibilityCommandBus.
- **Receiver-mode UDP reply via lastSenderAddress** — simplest bidirectional approach
  without changing the Transport interface. The receiver learns the bridge's address
  from the first incoming packet and replies to it.
- **Sensitivity applied on receiver side** — keeps the bridge's hot path free of scaling.
  The bridge sends raw HID deltas; the receiver scales them for its screen.
- **PING latency via lastPingSentAtMs** — approximate (doesn't track multiple in-flight
  PINGs) but sufficient. Full sequence-keyed tracking deferred to Phase 5.

### Lessons
- `UdpTransport` in receiver mode (isSender=false) had `config.targetIp` = empty,
  so startSendLoop would call `InetAddress.getByName("")` and fail. Always split
  send logic by mode in bidirectional UDP scenarios.
- `AccessibilityNodeInfo.textSelectionStart` returns -1 when there is no selection;
  use `.coerceIn(0, text.length)` before using as a string index.
- `KeyEvent(0, 0, ACTION_DOWN, keyCode, 0, metaState).unicodeChar` is the clean way
  to resolve printable characters from Android key codes without a manual lookup table.

---

## Session 007 — Phase 5 Remainder + Phase 4 Robust Error Handling

**Date**: 2026-07-21
**Agent**: Claude (Replit)
**Version**: 0.4.0 → 0.5.0
**Status**: ✅ Complete (pending CI)

### Context
Continued from session 006. Pairing, reconnect, and packet-loss detection were done. The following remained:
- Phase 5: rolling latency average, latency trace timestamps
- Phase 4: robust error handling for accessibility service (secure window detection, exception wrapping)
- CHANGELOG.md had no `[0.4.0]` entry (session 006 changes were never recorded)
- DiagnosticsScreens did not show pairing, reconnect, or drop-count fields despite them existing in DiagnosticsData

### Accomplished

#### DiagnosticsData (new fields)
- `latencyAvgMs: Long` — rolling 10-sample PING/PONG average
- `captureToSendUs: Long` — bridge hot-path time µs (event emission → UdpTransport.send return)
- `receiveToInjectUs: Long` — receiver command bus dispatch time µs
- `isSecureWindow: Boolean` — True when accessibility injection blocked (lock screen etc.)
- `lastInjectionError: String?` — last injectKeyCode/injectText exception message

#### DiagnosticsManager
- `recordLatency()` now maintains a 10-entry `ArrayDeque<Long>` behind a `synchronized` lock
- Computes rolling average on every PONG receipt; writes `latencyAvgMs` to DiagnosticsData

#### AccessibilityCommandBus
- Added `private val lastInjectUs = AtomicLong(0L)` — timed in `commandFlow.collect` lambda
- `handleEvent()` now wrapped with `System.nanoTime()` before/after; stores elapsed µs
- Added `fun getLastInjectUs(): Long` — exposes timing for ReceiverService flush
- Added `import com.inputbridge.diagnostics.DiagnosticsManager`
- Added `import java.util.concurrent.atomic.AtomicLong`

#### InputBridgeAccessibilityService
- `injectKeyCode()`: checks `rootInActiveWindow == null` → sets `isSecureWindow = true`, returns early; wraps `injectKeyCodeInternal()` in try-catch → writes `lastInjectionError`
- `injectText()`: same secure-window guard and try-catch wrapping
- Logic extracted to `injectKeyCodeInternal()` and `injectTextInternal()` private helpers

#### BridgeService
- Added `private val lastCaptureToSendUs = AtomicLong(0L)`
- `captureJob`: records `System.nanoTime()` before event processing; stores elapsed µs after successful send
- `counterFlushJob`: flushes `captureToSendUs` to DiagnosticsData every 1 s
- Added `import java.util.concurrent.atomic.AtomicLong`

#### ReceiverService
- `counterFlushJob`: reads `AccessibilityCommandBus.getLastInjectUs()` every 1 s (API 24+ guard); writes to `receiveToInjectUs`

#### UI
- `BridgeScreen`: latency line now shows `${latencyMs}ms · avg ${latencyAvgMs}ms`
- `DiagnosticsScreen` (bridge): added rows — Latency (last), Latency (avg), Capture→Send, Paired, Reconnecting
- `ReceiverDiagnosticsScreen`: added rows — Secure Window, Paired (with peer IP), Session PIN, Latency (avg), Recv→Inject, Dropped (seq), Inject Error section

#### Documentation
- `CHANGELOG.md`: added `[0.5.0]` entry (this session) and missing `[0.4.0]` entry (session 006)
- `TASKS.md`: Phase 5 latency/rolling-avg items checked; Phase 4 robust error handling checked
- `PROJECT_STATE.md`: version bumped to 0.5.0; phase completion updated (Phase 4: 90%, Phase 5: 95%)
- `ROADMAP.md`: updated phase completion percentages
- `AI_CONTEXT.md`: updated current milestone

### Files changed
- `diagnostics/src/main/kotlin/com/inputbridge/diagnostics/DiagnosticsData.kt` — 5 new fields
- `diagnostics/src/main/kotlin/com/inputbridge/diagnostics/DiagnosticsManager.kt` — rolling average
- `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/AccessibilityCommandBus.kt` — inject timing
- `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/InputBridgeAccessibilityService.kt` — robust error handling
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt` — capture-to-send trace
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt` — inject latency flush
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/BridgeScreen.kt` — avg latency display
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/DiagnosticsScreen.kt` — new diag rows
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/ui/screens/ReceiverDiagnosticsScreen.kt` — new diag rows
- `CHANGELOG.md`, `TASKS.md`, `PROJECT_STATE.md`, `ROADMAP.md`, `AI_CONTEXT.md`, `SESSION_LOG.md` — updated

### Key decisions
- Rolling average uses a synchronized `ArrayDeque` (not AtomicReference) because latency samples are written by a single coroutine — the synchronization cost is negligible
- Latency trace uses `System.nanoTime()` (monotonic) for µs precision, not `currentTimeMillis()` (wall clock)
- Secure window detection in `injectKeyCode`/`injectText` only — tap/swipe/longPress failures are silent (gesture dispatch handles this naturally and the user would notice clicks not landing)
- Inject timing measured in `AccessibilityCommandBus.handleEvent()` (the whole dispatch), not in ReceiverService, because `post()` is non-blocking; the command bus is where actual injection time accumulates

---

## Session 008 — CI Unblock + Automatic Release Workflow

**Date**: 2026-07-21
**Agent**: Claude (Replit)
**Version**: 0.5.0 → 0.5.1
**Status**: ✅ Complete (pushed, CI pending)

### Context
All CI runs #27–#31 were failing. The root cause was identified via GitHub Actions logs
using the stored GITHUB_PAT. Sessions 005–007 had committed new code but never confirmed
CI was green before the next session started.

### Root Cause Found

**BUG-011: `AccessibilityNodeInfo.ACTION_SELECT_ALL` does not exist**

CI error (all recent runs):
```
e: InputBridgeAccessibilityService.kt:407:80 Unresolved reference 'ACTION_SELECT_ALL'.
```

`AccessibilityNodeInfo` has no `ACTION_SELECT_ALL` constant. The correct approach for
Ctrl+A (select all text) is `ACTION_SET_SELECTION` with `SELECTION_START=0` and
`SELECTION_END=text.length` — both stable since API 18.

The error was introduced in session 005 (commit `2bc466f`). Sessions 006 and 007 fixed
other issues but did not catch this one because they committed on top of failing code
without checking CI.

### What Was Done

**Bug fix:**
- `InputBridgeAccessibilityService.handleCtrlKey()`: replaced
  `focused?.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)` with
  `ACTION_SET_SELECTION` + Bundle(start=0, end=text.length)

**New workflow:**
- `.github/workflows/release.yml` — two trigger modes:
  1. **Tag push** (`v*`): builds debug + optional signed release APKs, creates versioned
     GitHub Release marked as latest
  2. **CI auto-release**: triggered on every successful `Android CI` run on `main`,
     creates a pre-release tagged `build-{run_number}-{sha7}` with debug APKs attached
  - Signing is optional — if `SIGNING_KEYSTORE_BASE64` is absent, only debug APKs are uploaded
  - Uses `softprops/action-gh-release@v2`, `fail_on_unmatched_files: false` for missing release APKs

**Documentation:**
- `BUGS.md`: added BUG-011
- `CHANGELOG.md`: added [0.5.1] entry
- `PROJECT_STATE.md`: version → 0.5.1, CI table updated (runs #27–#31 all ❌ for BUG-011)
- `SESSION_LOG.md`: this entry

### Files changed
- `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/InputBridgeAccessibilityService.kt` — BUG-011 fix
- `.github/workflows/release.yml` — new automatic release workflow
- `BUGS.md`, `CHANGELOG.md`, `PROJECT_STATE.md`, `SESSION_LOG.md` — updated

### Key decisions
- Ctrl+A uses `ACTION_SET_SELECTION` (not a hypothetical `ACTION_SELECT_ALL`) — the
  correct accessibility API for selecting all text in a node since API 18
- Auto-release creates pre-releases (not stable releases) to avoid polluting the release
  list with every main commit; only tag pushes create stable releases
- `fail_on_unmatched_files: false` in the release action — gracefully skips release APK
  glob if signing secrets are absent, so CI never fails on missing keystore

---

## Next Session — Phase 6 (Bluetooth HID) or Phase 7 (Polish)

### Recommended next steps
1. **Phase 6 — Bluetooth HID**: `BluetoothHidTransport` implementation, HID descriptor, feature flag gating, graceful fallback
2. **Phase 7 — Polish**: Black screen mode, DataStore migration, emergency stop hotkey, auto-start UI toggle
3. **Hot-path audit**: profile allocation rate in `captureJob` at 125 Hz mouse events

### Entry files for Phase 6
- `transport-bluetooth-hid/src/main/kotlin/com/inputbridge/transport/bluetooth/BluetoothHidTransport.kt` — Phase 6 stub to implement
- `shared-core/src/main/kotlin/com/inputbridge/core/config/FeatureFlags.kt` — `BLUETOOTH_HID_ENABLED` flag

---

## Next Session — Phase 3 Remainder + Phase 5

### Tasks
1. **Pairing flow** — shared token (16-byte SecureRandom), PAIR_REQUEST / PAIR_RESPONSE /
   PAIR_CONFIRM packet sequence, token persistence, source validation
2. **QR / code pairing UI** — display token on receiver, scan or enter on bridge
3. **Reconnect** — exponential backoff, max attempts, UI amber dot
4. **Packet loss detection** — sequence number gap detection

### Entry Criteria
- ✅ Phase 3 keep-alive + Phase 4 accessibility injection committed (2bc466f)
- CI run for 2bc466f must be green (check GitHub Actions)

### Files to Touch
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt`
- New: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/PairingScreen.kt`
- New: `app-receiver/src/main/kotlin/com/inputbridge/receiver/ui/screens/PairingScreen.kt`
- `shared-core/src/main/kotlin/com/inputbridge/core/config/AppConfig.kt` — add pairing token

---

## Session 006

**Date**: 2026-07-21
**Agent commit**: "feat: CI fix + pairing handshake + exponential-backoff reconnect + packet loss detection"
**Version**: 0.3.0 → 0.4.0

### Context
Continued from session 005. CI was failing for commit `2bc466f` due to missing `:diagnostics` dependency in accessibility-receiver. Phase 3 remainder (pairing) and Phase 5 (reconnect) were 0% implemented.

### Accomplished

#### BUG-010 Fix (CI blocker)
- `accessibility-receiver/build.gradle.kts`: added `implementation(project(":diagnostics"))`
- Root cause: `InputBridgeAccessibilityService.kt` imports `DiagnosticsManager` but the module didn't declare the dependency

#### Protocol Layer
- `PacketSerializer.kt`: `buildPairRequestPayload/parsePairRequestPin`, `buildPairResponsePayload/parsePairResponseAccepted`
- `EventPacketFactory.kt`: `makePairRequest(pin)`, `makePairResponse(accepted)`, `makePairConfirm()`

#### DiagnosticsData (new fields)
- `isPaired`, `sessionPin`, `pairedPeerIp`
- `isReconnecting`, `reconnectAttempts`, `lastReconnectAttempt`
- `packetsDroppedSequence`

#### Transport
- `UdpTransport.getLastSenderIp()`: exposes last sender's IP for source validation

#### BridgePreferences / ReceiverPreferences
- `BridgePreferences`: `pairingPin`, `isPaired`, `setPinAndClearPairing()`
- `ReceiverPreferences`: `sessionPin`, `pairedBridgeIp`, `isPaired`, `generateNewPin()`

#### BridgeService (full rewrite)
- New architecture: `startIncomingLoop()` → `doPairing()` → `startPingLoop()` → `startWatchdog()`
- `startIncomingLoop()`: collector registered BEFORE PAIR_REQUEST sent (no race)
- `doPairing()`: sends PAIR_REQUEST, awaits CompletableDeferred with 10 s timeout
- `startWatchdog()`: 15 s grace period, then checks every 3 s for PONG timeout
- `triggerReconnect()`: exponential backoff (1→30 s), up to 10 attempts, guarded by AtomicBoolean
- On reconnect: re-pairs if needed, re-registers all loops

#### ReceiverService (full rewrite)
- Generates 6-digit PIN on first run (persisted)
- `PAIR_REQUEST` handling: validates PIN, sends `PAIR_RESPONSE`, records bridge IP
- `PAIR_CONFIRM` handling: logs confirmation
- Source IP validation: drops packets from non-paired senders (except PAIR_REQUEST)
- `DISCONNECT`: clears pairing state
- Packet loss detection: sequence-number gap counting in `droppedSequencePackets` AtomicLong

#### ViewModels
- `BridgeViewModel`: `isPaired`, `setPairingPin(pin)`, `clearPairing()`, reconnect state in `connectionLabel`
- `ReceiverViewModel`: `sessionPin`, `isPaired`, `generateNewPin()`, init ensures PIN exists

#### UI
- `ConnectionScreen` (receiver): large PIN display, paired/unpaired status, "REGENERATE PIN" button
- `SettingsScreen` (bridge): "Pairing" section with 6-digit entry, status, "Clear" button

#### Documentation
- Created `replit.md`
- Updated `PROJECT_STATE.md` to version 0.4.0
- Updated `AI_CONTEXT.md` current milestone
- Updated `ROADMAP.md` phase completion %
- Updated `TASKS.md` — checked off Phase 3 remainder + Phase 5 partial items
- Added `BUGS.md` entry BUG-010

### Key decisions
- PIN is 6 digits random (100000–999999), generated by `Random.nextInt` — simple, no QR dependency
- QR code display deferred to Phase 7 (not needed for offline LAN use)
- Pairing skipped entirely if `pairingPin` is empty (open/trusted-network mode)
- Already-paired sessions skip handshake on reconnect (bridge stores `isPaired` in prefs)
- Watchdog 15 s grace period avoids false reconnects during initial UDP socket setup

### Files changed
- `accessibility-receiver/build.gradle.kts` — BUG-010 fix
- `protocol/src/main/kotlin/com/inputbridge/protocol/PacketSerializer.kt` — pairing payloads
- `protocol/src/main/kotlin/com/inputbridge/protocol/EventPacketFactory.kt` — pairing factory methods
- `diagnostics/src/main/kotlin/com/inputbridge/diagnostics/DiagnosticsData.kt` — new fields
- `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt` — getLastSenderIp()
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/prefs/BridgePreferences.kt` — pairing prefs
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/prefs/ReceiverPreferences.kt` — pairing prefs
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt` — full rewrite
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt` — full rewrite
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/viewmodel/BridgeViewModel.kt` — pairing VM
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/viewmodel/ReceiverViewModel.kt` — PIN VM
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/SettingsScreen.kt` — PIN entry
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/ui/screens/ConnectionScreen.kt` — PIN display
- `replit.md` — created
- `PROJECT_STATE.md`, `AI_CONTEXT.md`, `ROADMAP.md`, `TASKS.md`, `BUGS.md`, `SESSION_LOG.md` — updated

