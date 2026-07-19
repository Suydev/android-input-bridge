# DECISIONS.md

Every important design decision, recorded permanently.
Mark old decisions as SUPERSEDED rather than deleting them.

---

## DEC-001 — Binary protocol over JSON

**Date**: 2025-07-19
**Problem**: What wire format to use for input event packets?
**Alternatives considered**:
- JSON over TCP — human-readable, but ~10× larger packets and TCP ACK overhead
- Protobuf — compact, but adds tooling complexity for a small protocol
- Custom binary — smallest possible, hand-written, matches hot-path requirements

**Chosen solution**: Custom binary protocol (see PROTOCOL.md)
**Reasoning**: Input events are tiny (4–16 bytes). JSON serialization is measurably slower and produces larger packets. For a fire-and-forget UDP stream, custom binary is the correct trade-off.
**Trade-offs**: Less human-readable during debugging. Mitigated by PacketSerializer unit tests and diagnostic logging.
**Future reconsideration**: If we ever need cross-language clients (e.g. a desktop bridge), consider Protobuf at that point.

---

## DEC-002 — UDP as default transport

**Date**: 2025-07-19
**Problem**: TCP or UDP for live input event streaming?
**Alternatives considered**:
- TCP — reliable, ordered, but ACK overhead adds 1–5ms per event on local LAN
- UDP — unreliable, unordered, but 0 ACK overhead; packet loss on local LAN is < 0.1%

**Chosen solution**: UDP for all live input events; TCP optionally for setup/pairing only
**Reasoning**: Mouse move events at 125Hz produce a packet every 8ms. A TCP ACK round-trip of even 2ms wastes 25% of the window. A dropped mouse-move packet is invisible to the user; a blocked TCP send is a visible stutter.
**Trade-offs**: Out-of-order delivery is possible but irrelevant — we don't reconstruct ordered streams. Sequence numbers are present for latency tracking, not ordering.
**Future reconsideration**: If we move to Wi-Fi Direct (which may implement its own reliable layer), revisit.

---

## DEC-003 — PacketType IDs are frozen

**Date**: 2025-07-19
**Problem**: How to version the protocol across app updates?
**Alternatives considered**:
- Re-number types freely across versions — breaks paired devices on asymmetric updates
- Semantic versioning per type — too complex for a small protocol

**Chosen solution**: `PROTOCOL_VERSION` byte in every packet header. PacketType IDs are permanent once assigned. New types always get a new ID from the reserved range 0x60+.
**Reasoning**: Bridge and receiver may be at different versions (one updated, one not). Fixed IDs allow the receiver to correctly ignore unknown types rather than misinterpret them.
**Trade-offs**: Cannot repurpose IDs. The ID space (256 entries) is more than sufficient.
**Future reconsideration**: If we ever need a breaking protocol change, increment PROTOCOL_VERSION and add a version negotiation handshake.

---

## DEC-004 — Accessibility over overlay for input injection

**Date**: 2025-07-19
**Problem**: How to inject input on the receiver without root?
**Alternatives considered**:
- SYSTEM_ALERT_WINDOW overlay — can draw UI but cannot inject input into other apps
- adb injection (INPUT_FRAMEWORK) — requires adb or root
- Accessibility Service — can inject gestures, text, and navigation to any visible app

**Chosen solution**: Accessibility Service with `canPerformGestures=true`
**Reasoning**: Only Accessibility provides non-root gesture injection. The approach is honest about limitations (no real cursor, no secure window injection).
**Trade-offs**: User must manually enable the accessibility service. Certain ROMs kill it. Some system windows block injection.
**Future reconsideration**: Bluetooth HID (DEC-005) is the correct solution for a real cursor. Accessibility remains the fallback.

---

## DEC-005 — Bluetooth HID as Path A for real cursor

**Date**: 2025-07-19
**Problem**: How to provide a real hardware cursor on the receiver?
**Alternatives considered**:
- Accessibility cursor overlay — draws a dot but cannot interact as a real cursor
- ADB mouse injection — requires ADB over network (complex, security risk)
- Bluetooth HID Device API — Android's built-in HID device role

**Chosen solution**: Bluetooth HID Device API (android.bluetooth.BluetoothHidDevice), feature-flagged, Phase 6
**Reasoning**: BluetoothHidDevice (API 28+) lets the phone register as a Bluetooth input device. The tablet sees it as a real keyboard+mouse and provides a system cursor. This is the only non-root path to a real cursor.
**Trade-offs**: Not all devices support the HID Device role. Bluetooth latency is higher than UDP on the same LAN. Requires additional BT pairing flow.
**Future reconsideration**: If the Redmi 9's Bluetooth stack doesn't support HID Device role in practice, document this as a confirmed limitation and focus on accessibility mode.

---

## DEC-006 — Koin for dependency injection

**Date**: 2025-07-19
**Problem**: DI framework for Android modules?
**Alternatives considered**:
- Hilt — annotation processor, more boilerplate, requires kapt/ksp setup
- Dagger — very heavy for this project size
- Manual DI — viable but scales poorly
- Koin — runtime DI, minimal boilerplate, Compose-friendly

**Chosen solution**: Koin 3.x
**Reasoning**: Koin integrates cleanly with Compose ViewModels and requires minimal annotation processing setup. The project is not at a scale where Hilt's compile-time guarantees are worth the complexity cost.
**Trade-offs**: Runtime DI means DI errors surface at runtime, not compile time. Mitigated by integration tests.

---

## DEC-007 — DiagnosticsManager as a singleton

**Date**: 2025-07-19
**Problem**: How should all modules report their state for the diagnostics UI?
**Alternatives considered**:
- Injected via DI — requires wiring through all modules
- Shared StateFlow in a ViewModel — not accessible from non-UI modules (services, transport)
- Singleton — simple, accessible from any module

**Chosen solution**: `DiagnosticsManager` object (Kotlin singleton)
**Reasoning**: Services, transport layers, and input capture all need to update diagnostics. A singleton avoids complex DI threading. The update function is synchronized via Flow's thread-safe emit.
**Trade-offs**: Global state makes unit testing slightly harder. Mitigated by testable update lambdas.
**Future reconsideration**: If DiagnosticsManager becomes a bottleneck, switch to a per-module Flow and aggregate at the ViewModel layer.
