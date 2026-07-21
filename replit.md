# InputBridge — Replit Project Notes

## Project Overview

InputBridge is an offline Android input bridge. A Portronics Key2 Combo USB keyboard+mouse receiver connects to a Redmi 9 phone (Bridge app) via OTG; input is forwarded over local Wi-Fi UDP to a OnePlus Pad Go tablet (Receiver app) which injects it via Android Accessibility Service.

**Repository:** https://github.com/Suydev/android-input-bridge  
**Language:** Kotlin (100%)  
**Build system:** Gradle 8.7 with AGP 8.4.2, multi-module  

## Architecture

```
[Portronics Key2 USB] → OTG → [Redmi 9: app-bridge]
                                  ↓ UsbInputCapture
                                  ↓ EventPacketFactory → binary packet
                                  ↓ UdpTransport (UDP LAN)
                               [OnePlus Pad Go: app-receiver]
                                  ↓ UdpTransport receive
                                  ↓ PacketToEventConverter
                                  ↓ AccessibilityCommandBus
                                  ↓ InputBridgeAccessibilityService
```

## Modules

| Module | Purpose |
|---|---|
| `app-bridge` | Bridge APK (runs on Redmi 9) |
| `app-receiver` | Receiver APK (runs on OnePlus Pad Go) |
| `shared-core` | InputEvent models, AppConfig, FeatureFlags, BridgeLogger |
| `protocol` | Packet, PacketSerializer, EventPacketFactory, PacketToEventConverter |
| `input-capture` | UsbInputCapture, KeyMap |
| `transport-wifi` | UdpTransport |
| `transport-bluetooth-hid` | BluetoothHidTransport (Phase 6 stub) |
| `accessibility-receiver` | InputBridgeAccessibilityService, AccessibilityCommandBus |
| `diagnostics` | DiagnosticsData, DiagnosticsManager |
| `build-logic` | Convention plugins |

## Current Phase

**Phase 5 (Latency + Reconnect) — started**

Phases 1–4 complete. Phase 3 remainder (pairing) and Phase 5 (reconnect) implemented in session 006.

## How to Build

This project builds Android APKs — it cannot run on Replit directly.

```bash
./gradlew :app-bridge:assembleDebug :app-receiver:assembleDebug
```

APKs are produced by GitHub Actions CI on every push.

## Key Files for New Sessions

Start here:
1. `AI_CONTEXT.md`
2. `PROJECT_STATE.md`
3. `TASKS.md`

## User Preferences

- Keep the repository resumable by any AI agent at any time
- Every meaningful change must be reflected in documentation files
- No cloud dependency, no accounts, offline-first
- Low latency is critical — no blocking on the hot path
- Push all changes to GitHub after each session
