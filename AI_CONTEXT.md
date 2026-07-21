# AI_CONTEXT.md — InputBridge

**READ THIS FIRST before writing any code.**

If you are a new AI agent, continue development from PROJECT_STATE.md and TASKS.md before making architectural changes.

---

## Purpose

InputBridge turns a Redmi 9 phone into a keyboard/mouse bridge for a OnePlus Pad Go tablet. The Portronics Key2 Combo USB receiver plugs into the Redmi via OTG. Input events are forwarded locally (Wi-Fi UDP or Bluetooth HID) to the OnePlus.

## Hardware

| Device | Android API | RAM | Notes |
|---|---|---|---|
| Redmi 9 (Carbono) | API 29 (Android 10+) | 4GB | Bridge sender, runs app-bridge |
| OnePlus Pad Go | API 33+ (Android 13+) | 8GB | Receiver, runs app-receiver |
| Portronics Key2 Combo | — | — | USB HID keyboard + mouse receiver (2.4GHz dongle) |
| USB OTG adapter | — | — | USB-A to USB-C, connects receiver to Redmi |

## Project philosophy

- **Offline-first**: no internet required after install
- **Latency-first**: every design decision optimises for lowest input delay
- **Honesty**: the UI never lies about what mode is active or what Android can/cannot do
- **Modularity**: input capture, transport, and injection are separate modules that can evolve independently

## Latency targets

- Input capture → packet send: < 2ms
- Network round-trip (same LAN): < 5ms
- Total end-to-end: < 10ms for keyboard, < 20ms for mouse

## Architecture summary

```
Redmi 9:
  UsbInputCapture → InputEvent → EventPacketFactory → Packet → UdpTransport → UDP/Wi-Fi
                                                                              ↓
OnePlus Pad Go:                                              UdpTransport → Packet
                                                         PacketSerializer → InputEvent
                                                       AccessibilityCommandBus → tap/swipe/text
```

## Repository layout

```
app-bridge/              MainActivity + BridgeService + UI screens
app-receiver/            MainActivity + ReceiverService + UI screens
shared-core/             InputEvent model, AppConfig, FeatureFlags, BridgeLogger
protocol/                Packet, PacketType, PacketSerializer, EventPacketFactory
input-capture/           InputCapture interface, UsbInputCapture, KeyMap
transport-wifi/          Transport interface, UdpTransport, WifiDirectTransport (stub)
transport-bluetooth-hid/ BluetoothHidTransport (stub, Phase 6)
accessibility-receiver/  InputBridgeAccessibilityService, AccessibilityCommandBus
diagnostics/             DiagnosticsData, DiagnosticsManager
build-logic/             Convention plugins (AndroidApp, AndroidLibrary, AndroidCompose)
```

## Important modules

### shared-core
- `InputEvent` — sealed class hierarchy for all input (key, mouse, scroll, nav, text)
- `ModifierState` — compact modifier bitmask (serialises to 1 byte)
- `AppConfig` — configuration data classes
- `FeatureFlags` — feature toggles
- `BridgeLogger` — thin Timber wrapper (never log on hot path)

### protocol
- `PacketType` — 1-byte type IDs. **Do NOT change existing IDs — breaks pairing compatibility.**
- `Packet` — wire format: 14-byte header + payload
- `PacketSerializer` — stateless binary serializer, safe to call from multiple threads
- `EventPacketFactory` — converts InputEvent → Packet, generates sequence numbers

### input-capture
- `InputCapture` — interface: emit Flow<InputEvent>, never block main thread
- `UsbInputCapture` — reads USB HID interrupt transfers, parses keyboard (8-byte) and mouse (4-byte) boot protocol reports
- `KeyMap` — HID Usage ID → Android KeyEvent.KEYCODE_* mapping

### transport-wifi
- `Transport` — interface: connect/disconnect/send/incomingPackets/connectionState
- `UdpTransport` — DatagramSocket-based UDP, separate send and receive coroutines

### accessibility-receiver
- `InputBridgeAccessibilityService` — dispatchGesture for tap/swipe, performGlobalAction for nav
- `AccessibilityCommandBus` — singleton command bus between network layer and service
- Virtual cursor: mouse moves update cursorX/cursorY, clicks tap at that position

## Coding conventions

- Kotlin everywhere — no Java
- Jetpack Compose for all UI
- Coroutines + Flow for async (no RxJava, no LiveData)
- Koin for dependency injection
- Timber / BridgeLogger for all logging — never raw `Log.*` calls
- No logging on the hot path in production builds — check `FeatureFlags.LATENCY_TRACING_ENABLED`
- All flows: `MutableStateFlow` for state, `MutableSharedFlow` for events
- Foreground services must hold WakeLock while active

## Naming conventions

- `*Capture` — input capture implementations
- `*Transport` — network transport implementations
- `*Service` — Android services (foreground or accessibility)
- `*ViewModel` — Compose ViewModels
- `*Screen` — top-level Compose screens (stateful, receive ViewModel)
- `*CommandBus` — singleton dispatcher between layers

## Current assumptions

- The Portronics Key2 Combo reports as a standard HID boot protocol device (class 3, subclass 1/2)
- Both devices are on the same local Wi-Fi network or one acts as a hotspot
- Android 10+ (API 29) is the minimum — both devices satisfy this
- Battery optimization exemption must be granted by the user — we cannot grant it programmatically

## Known Android limitations

- **Accessibility services cannot create a real hardware cursor.** They inject synthetic gestures only.
- **Secure windows block accessibility injection.** Lock screen PIN entry will not work via accessibility.
- **MIUI/ColorOS may kill foreground services** despite WakeLock and FOREGROUND_SERVICE permission. User must enable autostart manually.
- **Bluetooth HID Device API** requires Android 9+ (API 28). Only works if the device's Bluetooth stack supports HID Device role (not all devices do).
- **Wi-Fi Direct group formation** is slow (several seconds). Not suitable for initial connection — use UDP for first connect, negotiate Wi-Fi Direct after.

## Important design decisions

See DECISIONS.md for full records. Short summary:
- **Protocol version locked at 1.** Never change existing PacketType IDs.
- **UDP is the default transport** — not TCP. Input events are fire-and-forget; TCP ACK overhead is unacceptable.
- **Binary protocol, not JSON** — packet size is critical on hot path.
- **Accessibility is Path B, not Path A.** Bluetooth HID is the correct path for real cursor support.
- **DiagnosticsManager is a singleton** accessed by all modules — simplest approach for Phase 1.

## Do NOT change

- `PacketType` enum values (ID bytes) — changing breaks pairing compatibility
- `Packet.PROTOCOL_VERSION` without a migration plan
- `InputCapture.events` flow contract — must emit on background dispatcher
- `AccessibilityCommandBus` singleton lifecycle (tied to service)

## Safe to change

- UI layouts and colors (Compose)
- `AppConfig` fields (adding new fields is safe; removing is a breaking change)
- `FeatureFlags` constants (except `BLUETOOTH_HID_ENABLED`)
- `DiagnosticsData` fields (additive changes are safe)
- Transport implementations (must implement `Transport` interface)

## Current milestone

**Phase 5 complete (95%)** — Latency trace (capture→send µs, recv→inject µs) and rolling 10-sample PONG average added. Phase 4 robust error handling (secure window detection + try-catch in injectKeyCode/injectText) complete. Phases 1–5 done. Phase 6 (Bluetooth HID) is next.

## Current TODO

See TASKS.md — Phase 6 (Bluetooth HID) and Phase 7 (Polish) are the remaining phases.

## Current blockers

None known. CI run for session 007 changes pending (need GitHub PAT to push and monitor).

## Future roadmap

See ROADMAP.md

## Expected behavior (current)

Both APKs build successfully. Bridge app:
- Connects to receiver via UDP
- Performs PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM handshake using a 6-digit PIN
- Sends PING every 1 s; computes round-trip latency on PONG
- Reads USB HID keyboard+mouse events and forwards them as binary packets
- Auto-reconnects on PONG timeout (exponential backoff, up to 10 attempts)

Receiver app:
- Displays session PIN on Connection screen
- Validates incoming PAIR_REQUEST, sends PAIR_RESPONSE, records bridge IP
- Drops packets from unknown IPs after pairing
- Injects keyboard/mouse/scroll events via AccessibilityService
- Detects sequence-number gaps for packet-loss estimation

## Current testing status

Unit tests exist for protocol serialization and input event models. All tests pass locally.
Manual hardware test (Portronics Key2 Combo) not yet performed.

## Recommended next implementation step

**Phase 5 remainder**: rolling latency average display, latency trace timestamps across pipeline stages.
**Phase 4 remainder**: robust error handling for accessibility service disconnect and secure windows.
