---
name: InputBridge project setup
description: Key facts about the InputBridge Android project — where docs live, how to resume, what modules exist
---

# InputBridge project setup

**Why:** Every new agent session must read the MD files first — the repo is the single source of truth.

**How to apply:** At session start, read README, AI_CONTEXT, PROJECT_STATE, TASKS, SESSION_LOG in that order. Do not rely on chat history.

## Key facts
- Multi-module Kotlin/Gradle Android project (AGP 8.4.2, Kotlin 2.0.0, Compose BOM 2024.06.00)
- 9 modules: app-bridge, app-receiver, shared-core, protocol, input-capture, transport-wifi, transport-bluetooth-hid, accessibility-receiver, diagnostics
- GitHub: https://github.com/Suydev/android-input-bridge — CI on every push (GitHub Actions)
- PacketType IDs are FROZEN — never change existing byte values
- DiagnosticsManager is a singleton — update from any module, observed by UI via StateFlow
- Koin for DI (not Hilt), Coroutines+Flow (not RxJava), SharedPreferences now (DataStore in Phase 7)
- Min SDK 29 (Android 10), target SDK 35
