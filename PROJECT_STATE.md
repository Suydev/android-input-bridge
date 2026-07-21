# Project State

## Current Version
`0.3.0` — Phase 3 (PING/PONG keep-alive) + Phase 4 (accessibility injection) complete

## Repository
**GitHub:** https://github.com/Suydev/android-input-bridge  
**Branch:** `main`  
**Last commit:** `2bc466f` — Phase 3+4: PING/PONG keep-alive, full keyboard injection, accessibility detection, mouse sensitivity

## CI Status
| Run | SHA | Status | Notes |
|-----|-----|--------|-------|
| TBD | `2bc466f` | ⏳ pending | Phase 3+4 implementation — awaiting CI |
| #25 | `9931cb8` | ✅ success | Both APKs built, unit tests pass |
| #24 | `8dbec88` | ❌ failure | androidContext import missing, BuildConfig, ic_menu_receive |
| #23 | `774ba97` | ❌ failure | AAPT: Theme.Material.NoTitleBar.Fullscreen not found |

**CI pipeline:** `.github/workflows/ci.yml`
- Builds: `app-bridge` debug APK + `app-receiver` debug APK
- Tests: unit tests in `shared-core` and `protocol`
- APK artifacts retained for 30 days

## Phase Status
| Phase | Name                     | Status      | Complete |
|-------|--------------------------|-------------|----------|
| 1     | Project scaffold         | ✅ Done     | 100%     |
| 2     | USB input wiring         | ✅ Done     | 100%     |
| 3     | Keep-alive / PING-PONG   | ✅ Done     | 60%      |
| 4     | Accessibility receiver   | ✅ Done     | 80%      |
| 5     | Latency + reconnect      | 🔒 Blocked  | 0%       |
| 6     | Bluetooth HID (Path A)   | 🔒 Blocked  | 0%       |
| 7     | Release + distribution   | 🔒 Blocked  | 0%       |

## What This Session (005) Delivers

### Fixed — app was "dummy" (no real pipeline)

**UdpTransport** (`transport-wifi`):
- Receiver mode now tracks `lastSenderAddress` from every incoming datagram
- Sends (PONG, etc.) in receiver mode use that address — bidirectional UDP works
- Previously: `connect.targetIp` was empty in receiver mode, send would crash/fail silently

**InputBridgeAccessibilityService** (`accessibility-receiver`):
- `onServiceConnected()`: fetches real screen dimensions (WindowManager/DisplayMetrics),
  calls `AccessibilityCommandBus.setScreenSize()`, updates DiagnosticsManager
  (`accessibilityEnabled = true, accessibilityMode = "Accessibility"`)
- `onUnbind()`: clears DiagnosticsManager (`accessibilityEnabled = false`)
- `injectKeyCode(keyCode, modifiers)`: full keyboard injection —
  printable chars (via `KeyEvent.unicodeChar`), backspace, forward-delete,
  enter, arrows (char/word granularity, with shift-extend selection),
  home/end, escape → back, Ctrl+A/C/V/X
- `injectText(text)`: ACTION_SET_TEXT at cursor position, clipboard paste fallback
- `buildMetaState(modifiers)`: converts `ModifierState` → Android meta-state int

**AccessibilityCommandBus** (`accessibility-receiver`):
- Handles `KeyDown` → `svc.injectKeyCode(keyCode, modifiers)`
- Handles `KeyUp` → no-op (injection complete on KeyDown)
- Handles `TextInput` → `svc.injectText(text)`
- Handles `Scroll` → swipe gesture with `SCROLL_PIXEL_MULTIPLIER * sensitivity` scaling
- `mouseSensitivity` multiplier applied to all mouse delta updates
- `setSensitivity(Float)` API for external callers (0.1–10 range)
- `setScreenSize()` recentres the virtual cursor

**BridgeService** (`app-bridge`):
- PING loop: sends a PING packet every 1 s when transport is connected
- PONG response loop: collects `incomingPackets`, on PONG computes
  `latency = now - lastPingSentAtMs`, calls `DiagnosticsManager.recordLatency()`
- Jobs cleaned up in `onDestroy()` alongside existing jobs

**ReceiverService** (`app-receiver`):
- Handles `PacketType.PING` → sends PONG via `packetFactory.makePong(seq)`
- Handles `PacketType.KEEP_ALIVE` → logs, no further action
- Handles `PacketType.DISCONNECT` → updates DiagnosticsManager, updates notification
- Applies persisted mouse sensitivity on startup via `AccessibilityCommandBus.setSensitivity()`
- Input packets routed through `PacketToEventConverter` → `AccessibilityCommandBus.post()`

**ReceiverPreferences** (`app-receiver`):
- Added `mouseSensitivity: Float` field (default 1.0)

**ReceiverViewModel** (`app-receiver`):
- Added `setMouseSensitivity(Float)` — clamps to 0.1–5.0, persists to prefs,
  applies immediately to `AccessibilityCommandBus.setSensitivity()`
- Config pre-loaded with both port and sensitivity from prefs

**ReceiverSettingsScreen** (`app-receiver`):
- Sensitivity slider fully wired (0.1–5.0, live feedback label, changes apply immediately)
- Port field was already wired; added section header structure and dividers

## Known Issues / Limitations
- No pairing / token validation yet — any sender's packets are accepted (Phase 3 remainder)
- No automatic reconnect / exponential backoff yet (Phase 5)
- No launcher icons created (build warns, doesn't fail for debug APKs)
- Visual cursor overlay not yet implemented (Phase 7)
- `BluetoothHidTransport` is Phase 6 stub only
- Config persisted to SharedPreferences; DataStore migration in Phase 7
- Manual test on real Portronics Key2 Combo hardware not yet performed
- CI run for 2bc466f pending — check Actions tab for result

## Next Task — Phase 3 Remainder + Phase 5

### Phase 3 remaining
1. **Pairing** — shared token generation, PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM flow
2. **Pairing UI** — QR or manual code entry
3. **Token persistence** — SharedPreferences on both sides
4. **Packet source validation** — drop packets from un-paired senders

### Phase 5
1. **Automatic reconnect** — exponential backoff, max attempts
2. **UI state during reconnect** — amber dot + attempt counter
3. **Packet loss detection** — sequence number gap detection on receiver

## Manual Test Procedure (current build)

### Receiver (OnePlus Pad Go)
1. Install `app-receiver` APK
2. Enable InputBridge Receiver accessibility service in Settings → Accessibility
3. Open the app → tap "Start Receiver" → status dot turns blue
4. Verify: connection dot is blue, accessibility status shows "Active"

### Bridge (Redmi 9)
1. Install `app-bridge` APK
2. Open app → Settings → enter receiver's IP address → Save
3. Tap "Start Bridge"
4. Plug in Portronics Key2 Combo receiver via USB OTG
5. Grant the USB permission prompt
6. Diagnostics screen: USB device connected ✓, capture active ✓

### End-to-end verification
- Type on keyboard → characters appear in focused text field on tablet
- Move mouse → virtual cursor updates (gestures hit at new position)
- Left click → tap at cursor position
- Right click → long press
- Scroll wheel → scroll gesture on tablet
- Diagnostics → latencyMs should update to the PING round-trip time

## Architecture Quick Reference
```
[Portronics Key2] → USB OTG → [Redmi 9: BridgeService]
                                    ↓ UsbInputCapture (HID polling)
                                    ↓ EventPacketFactory.fromEvent()
                                    ↓ UdpTransport.send() (DatagramSocket)
                                    ↓ Wi-Fi LAN (UDP, fire-and-forget)
                                    ↓ PING every 1s (keep-alive + latency)
                                    ↓
                              [OnePlus Pad Go: ReceiverService]
                                    ↓ UdpTransport.incomingPackets
                                    ↓ PING → PONG (latency measurement)
                                    ↓ PacketToEventConverter.toInputEvent()
                                    ↓ AccessibilityCommandBus.post()
                                    ↓ InputBridgeAccessibilityService
                                    ↓ injectKeyCode / injectText / tap / swipe
```
