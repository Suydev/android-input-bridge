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

## Next Session — Phase 3: Network Transport + Pairing

### Tasks for Phase 3
1. **Pairing flow** — shared token generation (16-byte random), PAIR_REQUEST /
   PAIR_RESPONSE / PAIR_CONFIRM packet sequence, token persistence
2. **QR / code pairing UI** — display token on receiver, scan or enter on bridge
3. **Keep-alive** — PING/PONG on a 1s interval; latency measured and reported
4. **Reconnect** — exponential backoff, max attempts, UI amber dot during reconnect
5. **Packet source validation** — reject PAIR_REQUEST from unknown senders
6. **Wi-Fi Direct** — optional; group formation after initial UDP pairing

### Entry Criteria
- ✅ Phase 2 APKs build in CI (achieved: CI run #25 = success)
- Manual test: key presses appear in Diagnostics screen on both phones

### Files to Touch
- `app-bridge/service/BridgeService.kt` — add PING/PONG timer, reconnect logic
- `app-receiver/service/ReceiverService.kt` — respond to PING with PONG
- `protocol/EventPacketFactory.kt` — makePing/makePong already exist
- `shared-core/config/AppConfig.kt` — add pairing token field (or SecurityConfig)
- New: `app-bridge/ui/screens/PairingScreen.kt`
- New: `app-receiver/ui/screens/PairingScreen.kt`
- `PROJECT_STATE.md`, `TASKS.md` — update on completion
