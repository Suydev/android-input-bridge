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

## Next Session — Phase 2: USB Wiring
**Goal:** Wire UsbInputCapture into BridgeService pipeline

### Tasks for Phase 2
1. **BridgeService**: Replace stub pipeline with real coroutine scope;
   collect `UsbInputCapture.events`, pass to `UdpTransport.send()` via
   `EventPacketFactory.fromInputEvent()`
2. **USB attach/detach**: Handle `ACTION_USB_DEVICE_ATTACHED` broadcast;
   request `UsbManager.requestPermission()` before opening device
3. **ReceiverService**: Replace stub with real UDP receive loop;
   dispatch packets to `AccessibilityCommandBus`
4. **DiagnosticsManager**: Hook up real counters from service pipelines
5. **Manual test**: Plug in Portronics Key2 Combo receiver, confirm
   key events flow end-to-end (check DiagnosticsScreen on both phones)

### Entry Criteria
- Both APKs build cleanly in CI (check GitHub Actions on this repo)
- Unit tests pass (22 tests across shared-core + protocol)

### Files to Touch
- `app-bridge/src/main/kotlin/.../service/BridgeService.kt` — main pipeline
- `app-bridge/src/main/kotlin/.../ui/screens/WelcomeScreen.kt` — USB device name
- `app-receiver/src/main/kotlin/.../service/ReceiverService.kt` — UDP receiver
- `accessibility-receiver/src/main/kotlin/.../AccessibilityCommandBus.kt` — may need tuning
- `PROJECT_STATE.md` — update to Phase 2 in-progress
- `TASKS.md` — check off Phase 2 tasks as done
