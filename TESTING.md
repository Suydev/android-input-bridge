# TESTING.md

Test coverage plan and checklist.

---

## Automated Tests (Unit)

### shared-core — InputEventTest ✅
- ModifierState.NONE has all flags false
- ModifierState toByte/fromByte round-trips (all combinations)
- MouseButton.fromId returns correct button
- MouseButton.fromId defaults to LEFT for unknown ID
- AndroidNavAction.fromId returns correct action
- KeyDown, MouseMove, Scroll, TextInput field correctness

### protocol — PacketSerializerTest ✅
- KEY_DOWN round-trip (keyCode, scanCode, modifiers)
- MOUSE_MOVE round-trip (dx, dy float precision)
- SCROLL round-trip (dx, dy)
- TEXT_INPUT round-trip (UTF-8, Unicode emoji)
- MOUSE_DOWN round-trip (all button IDs)
- SPECIAL_ACTION round-trip (BACK)
- PING round-trip
- Deserialize returns null for too-short data
- Deserialize returns null for wrong protocol version
- Deserialize returns null for unknown packet type
- ModifierState round-trips through byte
- ModifierState.NONE is all-false
- EventPacketFactory sequence numbers are monotonically increasing
- EventPacketFactory.fromEvent returns correct type

### To add (Phase 2):
- [ ] KeyMap: all HID usage IDs map to non-UNKNOWN Android keycodes
- [ ] KeyMap: modifier key IDs are correctly identified as modifiers

### To add (Phase 3):
- [ ] Pairing: token generation is 16 bytes, random, non-repeating
- [ ] Pairing: invalid token rejects packet
- [ ] Reconnect: exponential backoff timing

---

## Manual Tests

### Phase 1 Checklist

- [ ] `./gradlew :app-bridge:assembleDebug` succeeds
- [ ] `./gradlew :app-receiver:assembleDebug` succeeds
- [ ] All unit tests pass (`./gradlew test`)
- [ ] Bridge APK installs on Redmi 9
- [ ] Receiver APK installs on OnePlus Pad Go
- [ ] Bridge app launches without crash
- [ ] Receiver app launches without crash
- [ ] All screens accessible by navigation
- [ ] Service starts and foreground notification appears

### Phase 2 Checklist

- [ ] Portronics receiver plugged in → USB permission dialog appears
- [ ] Permission granted → input capture starts
- [ ] Key press → KEY_DOWN appears in logcat
- [ ] Key release → KEY_UP appears in logcat
- [ ] Mouse move → MOUSE_MOVE dx/dy correct in logcat
- [ ] Scroll up → SCROLL dy < 0 in logcat
- [ ] Scroll down → SCROLL dy > 0 in logcat
- [ ] Diagnostics screen shows correct USB device name
- [ ] Unplug receiver → input capture stops cleanly

### Phase 3 Checklist

- [ ] Pairing completes on first launch
- [ ] Second launch: auto-reconnects without re-pairing
- [ ] PING/PONG measures < 5ms latency on same LAN
- [ ] Disconnect Wi-Fi → status shows disconnected
- [ ] Reconnect Wi-Fi → auto-reconnects within 10 seconds
- [ ] Unknown sender packet → rejected (no crash)

### Phase 4 Checklist

- [ ] Keyboard typing works in Chrome address bar on tablet
- [ ] Backspace deletes character
- [ ] Enter submits forms
- [ ] Arrow keys navigate in text
- [ ] Mouse move → virtual cursor moves
- [ ] Left click → tap at cursor position
- [ ] Right click → long press
- [ ] Scroll → scrolls page in Chrome
- [ ] BACK action → navigates back
- [ ] HOME action → goes to home screen
- [ ] RECENTS → shows app switcher

---

## Performance Tests

- [ ] Measure end-to-end latency at 60Hz mouse input (target: < 20ms)
- [ ] Measure CPU usage during active input (target: < 5% on Redmi 9)
- [ ] Measure battery drain over 1 hour (target: < 8% per hour)
- [ ] Confirm no memory leak after 30 min of continuous use (observe DiagnosticsManager)

---

## Compatibility Tests

- [ ] Redmi 9 running Android 10 (API 29)
- [ ] Redmi 9 running MIUI 12
- [ ] OnePlus Pad Go running Android 13 (API 33)
- [ ] Test with OTG hub (additional devices connected simultaneously)
