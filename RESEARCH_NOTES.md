# Session 031 Research Notes

## Agent 1: Deep Research - USB HID Capture, Input Injection, WiFi Latency, Android Input Pipeline

### USB HID Input Capture (Android 10)
- `UsbDevice` + `UsbEndpoint` read for raw HID reports (most reliable for Android 10, no Google Play)
- `android.hardware.usb` permission from `<uses-feature android:name="android.hardware.usb.host"/>`
- HID report descriptor parsing for button/axis/absolute pointer
- Permission dialog with `<usb-device>` in res/xml/

### Alternative Input Capture
- `InputManager.InputDeviceListener` via reflection (system API, root/system only)
- `cat /dev/input/eventX` (requires root or system)
- `InputDevice.getMotionRange(MotionEvent.AXIS_X)` for device capabilities

### AccessibilityService Input Injection
- `dispatchGesture()` for touch simulation (API 24+)
- `performGlobalAction()` for Back, Home, Recents
- `AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION` for gesture monitoring
- Touch exploration returns raw + transformed events (can use `getSource()` to distinguish)

### Root / Shell Injection (most reliable)
- `input tap x y` (tap at coordinates)
- `input swipe x1 y1 x2 y2` (swipe/gesture)
- `input keyevent KEYCODE` (key press)
- `input text "string"` (text input)
- Process: `Runtime.getRuntime().exec(arrayOf("sh", "-c", command))` or `ProcessBuilder`
- Coordinates = physical device pixels

### Android Input Subsystem Pipeline
1. **Linux kernel** evdev → `/dev/input/eventX`
2. **InputReader** (C++) → reads events from EventHub, processes key codes, touch coordinates
3. **InputDispatcher** (C++) → dispatches to focused window via InputChannel (Unix socket pair)
4. **Application** (Java/Kotlin) → `View.dispatchTouchEvent()` → `onTouchEvent()`
5. **AccessibilityService** → intercepts during dispatch, can consume/inject

### DispatchPipeline (System Server)
```
InputReader → InputDispatcher → DispatchEntry → connection → App window → View.onTouchEvent()
                                                    ↓
                                            AccessibilityService intercepts
                                            Can inject back via dispatchGesture()
```

### InputChannel
- Unix socket pair connecting system server → app
- Two-way: app can also send events back
- Events are binary structs, not text

### InputDevice.getDevice(int deviceId)
- `InputDevice.getDevice(event.deviceId)` returns device info
- `device.getName()` = "gpio-keys" for GPIO buttons
- `device.getVendorId()` and `device.getProductId()` identify USB devices
- `device.getMotionRange(MotionEvent.AXIS_X)` → resolution in device pixels

### Device Node Mapping
```
/dev/input/event0  →  GPIO keys
/dev/input/event1  →  USB HID device
/dev/input/event2  →  Touchscreen (if present)
```

### InputDeviceListener (via reflection)
```kotlin
val im = getSystemService(Context.INPUT_SERVICE) as InputManager
val method = InputManager::class.java.getMethod("registerInputDeviceListener", InputManager.InputDeviceListener::class.java, Handler::class.java)
method.invoke(im, listener, Handler(Looper.getMainLooper()))
```
- `onInputDeviceAdded(deviceId)`, `onInputDeviceChanged(deviceId)`, `onInputDeviceRemoved(deviceId)`

### WiFi UDP RTT Benchmarks (Android → Android, same network)
- **Same Wi-Fi LAN**: 0.5–5ms typical, often <1ms if on same AP
- **Cross-AP roaming**: 5–15ms
- **5 GHz vs 2.4 GHz**: 5 GHz ≈ 1–3ms, 2.4 GHz ≈ 2–8ms
- **Power-save mode**: 50–200ms spikes
- **WiFi Direct**: <1ms
- **Hotspot (phone AP)**: 0.5–2ms (local loopback)

### Bufferbloat Mitigation
- Keep buffers small: `SO_RCVBUF = 4096`, `SO_SNDBUF = 4096`
- `SO_PRIORITY = 6` (IPTOS_INTERACTIVE)
- `SO_REUSEADDR = true`
- Send packets immediately (no batching/Nagle)

### Threading for Low Latency
- `Thread.currentThread().priority = Thread.MAX_PRIORITY` on send/receive loops
- `android.os.Process.THREAD_PRIORITY_URGENT_AUDIO = -19` (most aggressive)
- `Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)` (-16)
- Single dedicated thread for send loop (no context-switching overhead)
- Avoid `CoroutineScope(Dispatchers.IO)` — coroutine overhead adds jitter

### Reducing Dispatch Hops
- Send input events directly from USB capture thread → UDP (minimal processing)
- No intermediate queues or buffering
- No UI update on the critical path
- `EventPacketFactory.makeMouseMove(dx, dy)` builds packets in ~100ns

### Android 10 Specifics
- Background execution limits (but foreground service is fine)
- USB access via `UsbManager` with permission intent
- `dispatchGesture()` available (API 24+, well-supported on Android 10)
- `AccessibilityService` is the most reliable injection method on non-root Android 10

---

## Agent 2: Pairing Flow, Protocol, Reverse Trackpad Analysis

### Pairing PIN Generation (Receiver Side)
- `ReceiverPreferences.generateNewPin()`: `Random.nextInt(100_000, 1_000_000)` → 6-digit string
- Stored in SharedPreferences key `"session_pin"`
- Called on first app launch (`ReceiverViewModel.init`), on service start, and on "REGENERATE PIN" button

### PIN Display (Receiver Side)
- `ConnectionScreen.kt` shows PIN in large 36sp bold monospace text
- Reads `viewModel.sessionPin` StateFlow
- Hint: "Enter this PIN in the bridge app -> Settings -> Pairing PIN"

### PIN Entry (Bridge Side)
- `SettingsScreen.kt` has text field labeled "Pairing PIN"
- `BridgeViewModel.setPairingPin(digits)` → stores via `prefs.setPinAndClearPairing(trimmed)`
- Clears `isPaired` flag to force re-pairing

### PAIR_REQUEST Payload Wire Format
- Packet header: version(1) + type_id(1) + sequence_no(4) + timestamp_ms(8) = 14 bytes
- PAIR_REQUEST type_id = 0x03
- Payload: UTF-8 encoded PIN string (6 bytes for 6-digit PIN)
- Total PAIR_REQUEST: 20 bytes

### PAIR_RESPONSE Wire Format
- Payload: single byte, 0x01 = accepted, 0x00 = rejected

### Complete Pairing Flow
```
1. Receiver generates PIN → displays on screen
2. User reads PIN → enters in bridge Settings screen
3. Bridge sends PAIR_REQUEST (type=0x03, payload=UTF-8 PIN)
4. Receiver compares received PIN vs stored PIN
5. If match: sends PAIR_RESPONSE(accepted=true), sets isPaired=true
6. If no match: sends PAIR_RESPONSE(accepted=false)
7. Bridge receives PAIR_RESPONSE → sets isPaired=true, sends PAIR_CONFIRM
8. Receiver receives PAIR_CONFIRM → updates transportConnected=true
```

### Existing Forward Trackpad Code
- `MouseTrackpadActivity.kt` on **bridge side**: captures touch → converts to CursorGoto/MouseMove → sends to receiver
- Includes 1-euro filter, acceleration, two-finger scroll, left/right click zones
- Full trackpad implementation for forward direction (bridge phone → receiver tablet)

### No Existing Reverse Trackpad Code
- **Receiver side**: No touch handlers, no MotionEvent processing, no OnTouchListener
- **AccessibilityCommandBus**: Only receives InputEvent from network, injects via accessibility service. No mechanism to capture touch and send back.
- **UdpTransport (receiver mode)**: `isSender = false`, only receives packets and sends control responses (PONG, PAIR_RESPONSE, PAIR_CONFIRM, DISCONNECT)
- **Protocol**: Unidirectional for input events (Bridge → Receiver). Only receiver→bridge packets are control responses.

### InputEvent Types Available for Reverse Trackpad
| Type | Description | Could Handle Reverse? |
|------|-------------|----------------------|
| KeyDown/KeyUp | Physical key press/release | Yes |
| MouseMove(dx, dy) | Relative mouse delta | Yes (touch → relative move) |
| MouseButtonDown/Up | Mouse button press/release | Yes (tap → click) |
| Scroll(dx, dy) | Scroll wheel | Yes (2-finger scroll → bridge scroll) |
| CursorGoto(x, y) | Absolute cursor position (0-1 normalized) | **Primary candidate** |
| TextInput | Composed text | Yes |
| ModifierStateChanged | Modifier state | Yes |
| NavigationAction | Android nav actions | Yes |

### CursorGoto Details
- Normalized x/y coordinates (0.0 to 1.0)
- `AccessibilityCommandBus` already handles CursorGoto (lines 178-184, inline no coroutine)
- `PacketType.CURSOR_GOTO` (0x29) exists in protocol, fully serializable
- Designed for "touch-to-cursor mapping for trackpad mode"

### Bridge-Side Injection (for reverse trackpad)
- Could use `dispatchGesture()` if bridge has accessibility service
- Could use `input tap x y` shell command (needs root/system)
- Could use `InputManager.injectInputEvent()` (system API, reflection)
- Most practical for non-root: the bridge would need its own accessibility service

### Gaps to Fill for Reverse Trackpad
1. **Receiver-side touch capture**: New Activity or overlay View with OnTouchListener
2. **Receiver-to-bridge input sending**: UdpTransport needs to send InputEvent packets (currently only control responses)
3. **Protocol**: Reuse existing CursorGoto packet type, or add direction flag
4. **Bridge-side injection**: Need injection method on bridge (accessibility service, shell, or system API)

---

## Key Architectural Insight: Auto-Discovery

- `AutoDiscovery.kt` already exists in the codebase (port 54322)
- Bridge broadcasts to find receivers, but UI isn't wired to auto-populate IP
- Bridge shows previously saved IP instead of clearing and scanning on launch
- Need to: (a) trigger auto-discovery when IP field is empty, (b) auto-populate discovered IP
- Bridge needs to detect hotspot mode to scan on correct network interface
- Auto-discovery is the solution to "no manual IP entry"
