# ROADMAP.md

Development phases with goals, deliverables, acceptance criteria, and status.

---

## Phase 1 — Project Scaffold

**Goal**: Establish the full project structure so all future phases have a clean foundation.

**Deliverables**:
- Multi-module Gradle project
- Convention plugins
- All module build files
- Shared protocol (serialization + models)
- UI shells for both apps
- GitHub Actions CI
- All documentation

**Acceptance criteria**:
- `./gradlew :app-bridge:assembleDebug :app-receiver:assembleDebug` succeeds
- All unit tests pass
- Both APKs install and launch without crashing
- All screens are navigable
- CI produces downloadable APK artifacts

**Dependencies**: None

**Risk level**: Low — no hardware required for Phase 1

**Completion**: 100% ✅

---

## Phase 2 — USB Input Capture

**Goal**: The Redmi 9 can read real keyboard and mouse input from the Portronics receiver
and forward packets to the receiver app over UDP.

**Deliverables**:
- BridgeService wired to UsbInputCapture
- USB device attach/detach broadcast handling
- USB permission request flow
- InputEvents flowing from UsbInputCapture into the diagnostics system
- ReceiverService wired to UdpTransport and AccessibilityCommandBus
- Config persistence (target IP, port) via SharedPreferences
- Manual test: key presses and mouse moves appear in Diagnostics screen

**Acceptance criteria**:
- Plugging in the Portronics receiver triggers USB permission prompt
- Key presses produce KEY_DOWN / KEY_UP events (visible in logs)
- Mouse moves produce MOUSE_MOVE events with correct dx/dy
- Scroll wheel produces SCROLL events
- Diagnostics screen shows correct USB device name and capture state
- ✅ CI: both debug APKs build on GitHub Actions

**Dependencies**: Phase 1

**Risk level**: Medium — depends on Portronics receiver reporting as standard HID boot protocol

**Completion**: 100% ✅ (code complete + CI green; manual hardware test pending)

---

## Phase 3 — Network Transport + Pairing

**Goal**: Bridge and receiver can communicate securely over local Wi-Fi.

**Deliverables**:
- UdpTransport wired in both apps (✅ already done in Phase 2)
- Pairing flow (QR or code exchange)
- Shared token validation
- Keep-alive / PING-PONG with latency measurement
- Reconnect logic

**Acceptance criteria**:
- Bridge and receiver can pair (one-time setup)
- Input packets arrive at receiver (verify via log)
- Latency is measured and displayed (< 20ms on same LAN)
- Disconnect and reconnect without manual intervention
- Unknown sender packets are rejected

**Dependencies**: Phase 2

**Risk level**: Medium

**Completion**: 0% 🔲

---

## Phase 4 — Accessibility Receiver

**Goal**: The OnePlus Pad Go responds to input from the bridge via accessibility injection.

**Deliverables**:
- Full AccessibilityCommandBus wiring
- Tap, long-press, swipe, scroll, nav actions working
- Text injection via clipboard paste
- Visual cursor dot overlay (optional)

**Acceptance criteria**:
- Keyboard typing works in a text field on the tablet
- Mouse movement updates virtual cursor position
- Left click taps at cursor position
- Right click produces long-press
- BACK, HOME, RECENTS navigation works
- Scroll works in a list view

**Dependencies**: Phase 3

**Risk level**: Medium — accessibility injection is less predictable than hardware input

**Completion**: 0% 🔲

---

## Phase 5 — Latency Optimisation + Reconnect

**Goal**: End-to-end latency is acceptable for typing and browsing.

**Deliverables**:
- Full latency trace (capture → send → receive → inject)
- Automatic reconnect with exponential backoff
- Packet loss detection via sequence number gaps

**Acceptance criteria**:
- End-to-end latency < 20ms on same LAN (measured, not estimated)
- Bridge reconnects automatically after 5 seconds of disconnect
- Latency display is accurate in both apps

**Dependencies**: Phase 4

**Risk level**: Low

**Completion**: 0% 🔲

---

## Phase 6 — Bluetooth HID

**Goal**: The Redmi 9 can appear as a Bluetooth keyboard+mouse to the OnePlus, providing a real hardware cursor.

**Deliverables**:
- BluetoothHidTransport implementation
- HID descriptor (keyboard + mouse combo)
- Feature flag toggle in settings
- Graceful fallback if HID Device API unsupported

**Acceptance criteria**:
- OnePlus Pad Go shows a real mouse pointer when HID mode is active
- Keyboard typing works through HID
- Mode indicator shows "HID" in both app UIs
- Disabling HID and switching to UDP works without restart

**Dependencies**: Phase 3

**Risk level**: High — Bluetooth HID Device role may not be available on Redmi 9's MIUI Bluetooth stack

**Completion**: 0% 🔲

---

## Phase 7 — Polish

**Goal**: App is ready for daily use.

**Deliverables**:
- Black screen mode (brightness → 0, pure black UI)
- All settings persisted to DataStore
- Emergency stop hotkey
- Help text and tooltips
- Auto-start toggle in UI
- Latency graph in diagnostics

**Acceptance criteria**:
- All settings survive app restart
- Black screen mode hides all UI chrome
- Emergency stop immediately halts input
- Onboarding is clear enough for a non-technical user

**Dependencies**: Phase 5

**Risk level**: Low

**Completion**: 0% 🔲
