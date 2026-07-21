---
name: InputBridge project setup
description: Android multi-module project; docs are the source of truth; read all .md files before coding
---

# InputBridge Project Setup

## Rule
Read README.md, AI_CONTEXT.md, PROJECT_STATE.md, TASKS.md, ROADMAP.md, DECISIONS.md, SESSION_LOG.md, BUGS.md, BUILD.md before touching any code. The docs are the handoff protocol between agents.

**Why:** Multiple AI agents work on this project across sessions. The docs record every decision, phase completion status, and known bug. Ignoring them causes regressions and duplicated work.

**How to apply:** First 10 actions of any new session must be reading these files.

## Current state (Session 011)
- All Phases 1–7 complete. Phase 8 (Wi-Fi Direct, DataStore, clipboard sync, macros) is future work.
- All bugs BUG-001 through BUG-031 resolved or documented. BUG-027 (USB bulkTransfer) and BUG-030 (scroll sensitivity) are DEFERRED.
- GitHub: https://github.com/Suydev/android-input-bridge
- Push with: `git remote set-url origin "https://$(printenv GITHUB_PAT)@github.com/Suydev/android-input-bridge.git" && git push origin main`
- CI builds on every push to main — check Actions tab for APK artifacts.

## Module layout
```
app-bridge/              Bridge APK (Redmi 9)
app-receiver/            Receiver APK (OnePlus Pad Go)
shared-core/             InputEvent, AppConfig, FeatureFlags, BridgeLogger
protocol/                Packet, PacketSerializer, EventPacketFactory
input-capture/           UsbInputCapture, KeyMap
transport-wifi/          UdpTransport
transport-bluetooth-hid/ BluetoothHidTransport (Phase 6)
accessibility-receiver/  InputBridgeAccessibilityService, AccessibilityCommandBus
diagnostics/             DiagnosticsData, DiagnosticsManager
build-logic/             Convention plugins
```
