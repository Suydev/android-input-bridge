# PROJECT_STATE.md

This file is the current brain of the project. It always reflects reality.

---

## Current Version

0.1.0

## Current Milestone

Phase 1 — Project Scaffold

## Current Branch

main

## Current Build Status

✅ Compiles (Gradle build system configured, all modules present)

## Current APK Status

Built via GitHub Actions CI. Download from Actions → latest run → Artifacts.

## Current Working Features

- Full multi-module Gradle project structure
- Build logic convention plugins (AndroidApp, AndroidLibrary, AndroidCompose)
- Protocol: packet serialization/deserialization (all types)
- Protocol: EventPacketFactory with atomic sequence numbers
- Shared: InputEvent sealed hierarchy (all event types)
- Shared: ModifierState (bitmask, serializes to 1 byte)
- Shared: AppConfig, FeatureFlags, BridgeLogger
- Input capture: InputCapture interface, UsbInputCapture scaffold, KeyMap (full HID→Android)
- Transport: Transport interface, UdpTransport scaffold
- Accessibility: InputBridgeAccessibilityService, AccessibilityCommandBus (virtual cursor)
- Diagnostics: DiagnosticsManager singleton, DiagnosticsData snapshot
- Bridge App UI: Welcome, Bridge (active screen), Settings, Diagnostics, Permissions, About
- Receiver App UI: Welcome, Connection, Accessibility Setup, Settings, Diagnostics
- Both apps: Foreground services (BridgeService, ReceiverService), BootReceiver
- GitHub Actions CI: debug APKs + unit tests + optional release APKs

## Incomplete Features

- Phase 2: UsbInputCapture not wired into BridgeService (scaffold only)
- Phase 3: UdpTransport not wired into services (scaffold only)
- Phase 3: Pairing/security (token exchange) not implemented
- Phase 4: AccessibilityCommandBus fully wired to receiver service
- Phase 4: Text injection via clipboard
- Phase 5: Reconnect logic, latency measurement
- Phase 6: BluetoothHidTransport
- Phase 7: Black screen mode, min brightness, macros, clipboard sync

## Broken Features

None known.

## Known Blockers

None.

## Current Priorities

1. Wire Phase 2: connect UsbInputCapture to BridgeService
2. Add USB broadcast receiver for device attach/detach events
3. Test with real Portronics Key2 Combo hardware

## Current Architecture

See AI_CONTEXT.md

## Current Input Mode

Phase B (Accessibility) — scaffold only, not yet wired

## Current Transport Mode

UDP — scaffold only, not yet wired

## Current Receiver Mode

Accessibility — service registered, not yet receiving real packets

## Next Immediate Task

Phase 2: Implement USB device attach/detach broadcast in BridgeService.
Wire UsbInputCapture.start() when device is detected.
Emit InputEvents through EventPacketFactory into UdpTransport.send().

## Estimated Remaining Work

- Phase 2: 1-2 sessions
- Phase 3: 2-3 sessions
- Phase 4: 2-3 sessions
- Phase 5: 1-2 sessions
- Phase 6: 1-2 sessions
- Phase 7: 1 session

## Notes For Next AI

- Read AI_CONTEXT.md first, then this file, then TASKS.md
- The protocol is binary (not JSON). PacketType IDs are frozen — never change them
- UsbInputCapture reads USB interrupt transfers with a 50ms timeout poll loop
- AccessibilityCommandBus is a singleton that holds a reference to the live accessibility service
- DiagnosticsManager is a singleton that all modules update via DiagnosticsManager.update { }
- The build-logic module uses convention plugins — read build-logic/build.gradle.kts before adding new plugins
- Min SDK is 29 (Android 10). Both target devices meet this requirement
