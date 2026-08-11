# BTMouse Reference APK Analysis (btmouse-jadx)

## Architecture Summary

The reference app (io.appground.blek v6.23.1) uses BLE HID or Classic Bluetooth HID
to send mouse/keyboard reports to a connected device. Key files analyzed:

---

## 1. POINTER TRAIL (Cursor Path Drawing)

### PointerPathView.java (utils/)
- Draws cursor trail with fading lines and a cursor dot
- Trail stored as LinkedList of timed points
- Each trail segment fades based on age (alpha decreases over time)
- Lines drawn between consecutive trail points with decreasing stroke width
- Cursor dot drawn as filled circle at current position
- Trail automatically clears after inactivity via coroutine timeout

### Key implementation details:
- Trail points: (x, y, timestamp, isStart)
- Alpha fading: older points have lower alpha = more transparent
- Stroke width: older points have thinner lines
- Clear timeout: coroutine launches delay, then clears trail list
- Click animation: radius expansion + alpha fade on click

---

## 2. TOUCH HANDLER (tx2.java)

### Mouse/Touch Input Processing
- Implements OnTouchListener and OnHoverListener
- Hover events (ACTION_HOVER_MOVE, ACTION_HOVER_ENTER) → mouse move
- Touch events → mouse move with button tracking
- Two velocity tracker objects for X and Y axes (friction-based)
- Range constraint: pointer position clamped to [-1, 1] normalized range

### Button mapping:
- getActionButton() → button byte flags:
  - 1 = left click
  - 2 = right click
  - 4 = middle click
  - 8 = back button
  - 16 = forward button

### Sensitivity:
- Two separate sensitivity scales (mouse move vs air mouse)
- Configurable via preferences (air_mouse_speed, mouse_pointer_speed)

### Friction-based velocity tracker (y.java):
- Tracks position with friction decay
- Computes velocity from position changes
- Time-based exponential decay for smooth movement
- Forward prediction for lag compensation

---

## 3. CALLBACK INTERFACE (sx2.java)

### Mouse Event Callback Interface
- `m(int x, int y)` → mouse move (delta X, delta Y)
- `t(int vertical, int horizontal)` → scroll wheel
- `k(byte button, boolean isDown)` → button press/release
- `p(boolean isConnected)` → connection state change
- `a()` → action (unknown purpose)
- `l()` → scroll action

---

## 4. BLE HID SERVICE (BleHidService.java)

### BLE HID Implementation
- GATT Server with HID Service UUID 0x1812
- Report characteristic (0x2A4D) for sending HID reports
- Sends via GATT notifications (notifyCharacteristicChanged)
- Advertising with HID Service UUID
- Per-device connection state tracking

### Mouse HID Report (a10.java):
- Report ID: 1
- Format: {buttons(5 bits), X(8-bit signed), Y(8-bit signed), Wheel(8-bit signed), Pan(8-bit)}
- Button bits: left=1, right=2, middle=4, back=8, forward=16
- Large movements split into multiple reports (clamped to -127..127)

### Keyboard HID Report (v00.java):
- Report ID: 1
- Format: {modifier(8 bits), reserved(8 bits), key1-key6(8 bits each)}
- Modifier bits: Ctrl, Shift, Alt, GUI (left/right)
- Consumer Control (Report ID 3): volume up/down

### Report Sending:
- xx1.java: wrapper with retry logic (up to 2 retries)
- zx1.java: base HID service class, abstract send method

---

## 5. CLASSIC BLUETOOTH HID (ClassicHidService.java)

### Classic BT HID Implementation
- Uses BluetoothHidDevice API (Android 9+)
- sendReport() to connected device
- Bond/connection state via BroadcastReceiver
- Registered as HID Device profile

---

## 6. KEYBOARD INPUT

### TextInputView.java
- Custom EditText for keyboard input
- TextWatcher monitors text changes
- Dispatches typed characters to callback
- Supports hidden input mode

### Text Input Flow:
1. User types on keyboard → TextInputView captures
2. TextWatcher computes additions/deletions
3. Characters sent via HID keyboard report
4. Backspace handled separately (count-based)
5. Clipboard paste fallback available

### Keyboard HID Report:
- 6-key rollover (6 simultaneous keys)
- Modifier byte for Ctrl/Shift/Alt/GUI
- Consumer control for media keys (volume)

---

## 7. KEY MAPPING (g92.java)

### Android Keycode Constants:
- Navigation: DPAD_UP(19), DPAD_DOWN(20), DPAD_LEFT(21), DPAD_RIGHT(22), CENTER(23)
- Volume: VOLUME_UP(24), VOLUME_DOWN(25)
- Keys: A(29), C(31), I(36), O(50), Q(52), R(53), S(54)
- Edit: TAB(61), SPACE(62), ENTER(66), DEL/Backspace(67), FORWARD_DEL(112)
- Navigation: ESCAPE(111), HOME(122), END(123), PAGE_UP(92), PAGE_DOWN(93)
- Clipboard: CUT(277), COPY(278), PASTE(279)

### Virtual Mouse Button Codes:
- -1000000001: Left click
- -1000000002: Right click
- -1000000003: Middle click
- -1000000004: Side button 1
- -1000000005: Side button 2
- -1000000006: Scroll up
- -1000000007: Scroll down
- -1000000008: Scroll left
- -1000000009: Scroll right

### Key Encoding:
- wi5.v(int): encodes keycode as (keyCode << 32) for comparison
- si5.s(KeyEvent): returns action (2=DOWN, 1=UP, 0=UNKNOWN)
- si5.l(KeyEvent): returns encoded keycode from KeyEvent

---

## 8. SETTINGS (t74.java, o74.java, w01.java)

### Mouse Settings (t74):
- activate_air_mouse: toggle air mouse
- air_mouse_speed: seekbar (sensitivity)
- touch_click_enabled: toggle tap-to-click
- show_mouse_buttons: position (top/bottom)
- visible_mouse_buttons: which buttons shown (left/right/middle/side1/side2)
- mouse_pointer_speed: seekbar (speed)
- show_scroll_bar: position (left/right)
- mouse_scroll_speed: seekbar
- mouse_invert_scroll: toggle
- mouse_jiggle_mode: dropdown (disabled, etc.)
- pen_drawing_mode: toggle

### Settings Data (o74):
- showMediaButtons, showNavigationButtons, showShortcutButtons
- useAirMouse, airMouseSpeed
- useDarkTheme, useOutlineTheme
- invertScrolling, penDrawingMode
- enabledTouchClick, mousePointerSpeed, scrollSpeed
- mouseJiggleMode, startFullScreen, keepScreenOn
- showOverLockScreen, showKeyboard, screenBrightness
- inputBarOption, keyboardLayoutSelection, keyboardLayoutActive
- hapticFeedback, scannerSendEnter, scannerContinuousMode
- volUpButton, volDownButton, passwordModeEnabled
- imeVisible, useAnalogStick

### Device Config (w01):
- Per-device settings stored in Room database
- macAddress, mousePointerSpeed, airMouseSpeed, scrollSpeed
- mouseJiggleMode, layoutScreen, layoutScreenLayoutId
- keyboardLanguageLayout, lastUsedDate, addedDate

---

## 9. KEY PATTERNS FOR INPUTBRIDGE

### What the reference app does well:
1. **Per-device settings** - stores sensitivity per connected device
2. **Friction-based velocity** - smooth movement with exponential decay
3. **Range constraint** - normalizes pointer position to [-1, 1]
4. **Split large movements** - splits mouse reports > 127 pixels into multiple reports
5. **Button byte flags** - efficient encoding for multiple buttons
6. **6-key rollover** - supports 6 simultaneous keyboard keys
7. **Consumer control** - media keys via HID Consumer Page
8. **Clipboard paste fallback** - when direct text injection fails
9. **Hidden input mode** - for secure text fields

### Mouse Report Format:
```
Report ID: 1
Byte 0: buttons (bit flags: L=1, R=2, M=4, Back=8, Forward=16)
Byte 1: X delta (signed 8-bit, -127 to 127)
Byte 2: Y delta (signed 8-bit, -127 to 127)
Byte 3: Wheel (signed 8-bit)
Byte 4: Pan (signed 8-bit)
```

### Keyboard Report Format:
```
Report ID: 1
Byte 0: modifier (bit flags: L-Ctrl=1, L-Shift=2, L-Alt=4, L-GUI=8, R-Ctrl=16, R-Shift=32, R-Alt=64, R-GUI=128)
Byte 1: reserved (0)
Bytes 2-7: key codes (up to 6 simultaneous keys)
```

### Consumer Control Report:
```
Report ID: 3
Bytes 0-1: usage (16-bit, e.g., Volume Up=0xE9, Volume Down=0xEA)
```
