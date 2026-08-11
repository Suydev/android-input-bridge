# LATENCY_CONTEXT.md — Complete Research Consolidation

> **Purpose:** All findings from 15 subagents + reference APK analysis + codebase audit in one file.
> Read this before making any latency or UX changes.

---

## 1. Full Pipeline Timing

```
Bridge Side (Redmi 9):
  Touch → handleTouch() [Main]        ~0ms
  → scope.launch [IO dispatch]        ~1-2ms (COROUTINE OVERHEAD)
  → PacketFactory.fromEvent           ~0.1ms
  → Channel.send                      ~0.1ms
  → UdpTransport.sendLoop [IO]        ~0.5ms
  → DatagramSocket.send()             ~0.01-0.15ms (kernel)

WiFi:
  Network transit (LAN)                1-5ms (target <3ms)

Receiver Side (OnePlus Pad Go):
  sock.receive()                      ~0.5ms
  PacketSerializer.deserialize        ~0.3ms
  SharedFlow.emit                     ~0.1ms
  AccessibilityCommandBus.collect     ~0ms (inline on IO)
  dispatchGesture()                   10-30ms (HARD FLOOR ~10ms Binder IPC)

Total: 3-15ms before dispatchGesture system overhead
Target: <10ms E2E (excluding dispatchGesture)
dispatchGesture hard floor: ~10ms
Root floor: <5ms (/dev/uinput or InputManager.injectInputEvent)
```

---

## 2. All Bottlenecks (Priority Ordered)

| # | Bottleneck | File:Line | Impact | Fix |
|---|---|---|---|---|
| **B1** | `buf.copyOf(dp.length)` on every receive | `UdpTransport.kt:272` | ~1ms + GC pressure | Pass length to deserialize: `deserialize(buf, dp.length)` |
| **B2** | DatagramPacket + ByteBuffer alloc per send | `PacketSerializer.kt` | ~0.5ms + GC | ThreadLocal/reuse ByteBuffer, reuse DatagramPacket |
| **B3** | SharedFlow broadcast for single consumer | `UdpTransport.kt` | ~0.3ms | Replace with Channel for 1:1 paths |
| **B4** | `select {}` in send loop | `UdpTransport.kt:222-226` | ~0.2ms | Spin-first tryReceive before select{} |
| **B5** | InputEvent data class alloc (125/sec) | `InputEvent.kt` | GC pressure | Inline/value class or raw floats |
| **B6** | `scope.launch` per mouse move | `MouseTrackpadActivity.kt:377-381` | ~1-3ms | Direct send or ring buffer, no coroutine |
| **B7** | No `requestUnbufferedDispatch()` | `MouseTrackpadActivity.kt:237` | 4-8ms vsync batching | Add `trackpadView.requestUnbufferedDispatch(event)` |
| **B8** | No historical sample processing | `MouseTrackpadActivity.kt:319` | 2-4ms (50-75% samples thrown) | Process `getHistoricalX/Y()` |
| **B9** | System.nanoTime() instead of kernel timestamp | `InputEvent.kt:30` | Wrong timing | Use `MotionEvent.eventTimeNanos` |
| **B10** | dispatchGesture cancels in-progress gesture | `InputBridgeAccessibilityService.kt:132` | 10-30ms cancel/restart | `willContinue=true` + `continueStroke()` |
| **B11** | DiagnosticsManager.update {} on hot path | `AccessibilityCommandBus.kt:182-185` | ~0.1ms + lock | AtomicLong counter, flush periodically |
| **B12** | `updateViewLayout()` Binder IPC per frame | `CursorOverlayService.kt:139` | 60 Binder calls/sec | translationX/Y + invalidate() |
| **B13** | GestureDescription built on Main thread | `AccessibilityCommandBus.kt:52-61` | ~1ms | Build on Default, dispatchGesture on Main |

---

## 3. All Code Changes (Exact Snippets)

### Change 1: Unbuffered Dispatch + Historical Samples

**File:** `MouseTrackpadActivity.kt`

```kotlin
// In onCreate(), after trackpadView setup:
trackpadView.setOnTouchListener { v, event ->
    v.requestUnbufferedDispatch(event)
    handleTouch(event)
    true
}
```

In `handleTouch()` ACTION_MOVE block — process ALL historical samples:
```kotlin
val historySize = event.historySize
for (h in 0 until historySize) {
    val hx = event.getHistoricalX(0, h)
    val hy = event.getHistoricalY(0, h)
    val hdx = hx - lastX
    val hdy = hy - lastY
    totalMovement += sqrt(hdx * hdx + hdy * hdy)
    if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {
        isDragging = true
        handler.removeCallbacks(longPressRunnable)
    }
    if (isDragging) {
        sendMouseMove(hdx * sensitivity, hdy * sensitivity)
    }
    lastX = hx; lastY = hy
}
// Then process current sample
val x = event.getX(0); val y = event.getY(0)
val dx = x - lastX; val dy = y - lastY
// ... same threshold + send logic
```

### Change 2: Direct UDP Send (Eliminate Coroutine Overhead)

**File:** `MouseTrackpadActivity.kt`
```kotlin
private fun sendMouseMove(dx: Float, dy: Float) {
    val event = InputEvent.MouseMove(dx = dx, dy = dy)
    val packet = packetFactory.fromEvent(event) ?: return
    val transport = udpTransport ?: return
    scope.launch(Dispatchers.IO) {
        transport.sendDirect(packet)
    }
}
```

**File:** `UdpTransport.kt` — add direct-send method:
```kotlin
suspend fun sendDirect(packet: Packet): Boolean {
    if (!isConnected) return false
    val sock = socket ?: return false
    val bytes = PacketSerializer.serialize(packet)
    val destination = if (isSender) {
        InetSocketAddress(InetAddress.getByName(config.targetIp), config.port)
    } else {
        lastSenderAddress ?: return false
    }
    return try {
        sock.send(DatagramPacket(bytes, bytes.size, destination))
        true
    } catch (e: Exception) {
        if (isConnected) BridgeLogger.w(TAG, "Direct send error", e)
        false
    }
}
```

### Change 3: Kernel Timestamps

```kotlin
private fun sendMouseMove(dx: Float, dy: Float, timestampNs: Long) {
    val event = InputEvent.MouseMove(dx = dx, dy = dy, timestampNs = timestampNs)
    // ...
}
// In handleTouch():
if (isDragging) {
    sendMouseMove(dx * sensitivity, dy * sensitivity, event.eventTimeNanos)
}
```

### Change 4: Cursor Smoothing (EMA)

**File:** `AccessibilityCommandBus.kt`
```kotlin
@Volatile private var smoothCursorX = 0f
@Volatile private var smoothCursorY = 0f
private const val SMOOTHING_FACTOR = 0.4f  // 0=instant, 1=infinite lag

// In post(), after updating cursorX/cursorY for MouseMove:
if (event is InputEvent.MouseMove) {
    smoothCursorX += (cursorX - smoothCursorX) * SMOOTHING_FACTOR
    smoothCursorY += (cursorY - smoothCursorY) * SMOOTHING_FACTOR
    _cursorPosition.value = Pair(smoothCursorX, smoothCursorY)
}
```

### Change 5: Velocity-Based Acceleration (Windows-Style Ballistics)

```kotlin
private var lastMoveTimeNs = 0L
private var velocityX = 0f; private var velocityY = 0f
private const val VELOCITY_SMOOTHING = 0.3f
private const val ACCEL_THRESHOLD_LOW = 200f   // px/s
private const val ACCEL_THRESHOLD_HIGH = 2000f  // px/s
private const val ACCEL_MAX = 3.0f

private fun computeAcceleration(dx: Float, dy: Float, nowNs: Long): Pair<Float, Float> {
    if (lastMoveTimeNs == 0L) { lastMoveTimeNs = nowNs; return dx to dy }
    val dtSec = (nowNs - lastMoveTimeNs) / 1_000_000_000f
    lastMoveTimeNs = nowNs
    if (dtSec <= 0f || dtSec > 0.1f) return dx to dy
    velocityX += (dx / dtSec - velocityX) * VELOCITY_SMOOTHING
    velocityY += (dy / dtSec - velocityY) * VELOCITY_SMOOTHING
    val speed = sqrt(velocityX * velocityX + velocityY * velocityY)
    val accel = when {
        speed < ACCEL_THRESHOLD_LOW -> 1.0f
        speed > ACCEL_THRESHOLD_HIGH -> ACCEL_MAX
        else -> 1.0f + (ACCEL_MAX - 1.0f) *
            (speed - ACCEL_THRESHOLD_LOW) / (ACCEL_THRESHOLD_HIGH - ACCEL_THRESHOLD_LOW)
    }
    return dx * accel to dy * accel
}
```

### Change 6: Gesture Continuation for Drag

```kotlin
private var currentStroke: GestureDescription.StrokeDescription? = null
private var isGestureActive = false

fun startDrag(x: Float, y: Float) {
    val path = Path().apply { moveTo(x, y) }
    currentStroke = GestureDescription.StrokeDescription(path, 0, 16, true)
    val gesture = GestureDescription.Builder().addStroke(currentStroke!!).build()
    isGestureActive = true
    dispatchGesture(gesture, null, null)
}

fun continueDrag(x: Float, y: Float) {
    val stroke = currentStroke ?: return
    val path = Path().apply { moveTo(x - 1f, y); lineTo(x, y) }
    currentStroke = stroke.continueStroke(path, 0, 16, true)
    dispatchGesture(GestureDescription.Builder().addStroke(currentStroke!!).build(), null, null)
}

fun endDrag(x: Float, y: Float) {
    val stroke = currentStroke ?: return
    val path = Path().apply { moveTo(x - 1f, y); lineTo(x, y) }
    currentStroke = stroke.continueStroke(path, 0, 16, false)
    dispatchGesture(GestureDescription.Builder().addStroke(currentStroke!!).build(), null, null)
    isGestureActive = false
}
```

### Change 7: Dedicated Injection Dispatcher

```kotlin
private val injectionDispatcher = Dispatchers.Main.limitedParallelism(1)
private val injectionScope = CoroutineScope(injectionDispatcher + SupervisorJob())
```

### Change 8: buf.copyOf Elimination

```kotlin
// UdpTransport.kt:272 — Before:
val packet = PacketSerializer.deserialize(buf.copyOf(dp.length)) ?: continue
// After:
val packet = PacketSerializer.deserialize(buf, dp.length) ?: continue

// PacketSerializer — new overload:
fun deserialize(data: ByteArray, length: Int): Packet? {
    if (length < Packet.HEADER_SIZE) return null
    val buf = ByteBuffer.wrap(data, 0, length).order(BYTE_ORDER)
    // ... rest unchanged
}
```

### Change 9: CursorOverlayService — translationX/Y

Replace `windowManager.updateViewLayout(view, params)` with:
```
StateFlow emission → store target (x,y) → Choreographer.postFrameCallback →
  on doFrame: view.translationX = target.x; view.translationY = target.y; view.invalidate()
```
- Multiple positions within one 16ms frame coalesce to single `invalidate()`
- `setLayerType(LAYER_TYPE_HARDWARE, null)` — GPU texture cache
- Skip `invalidate()` if delta < 1px

### Change 10: Hybrid Click — performAction Fallback

```kotlin
fun clickAtPosition(x: Float, y: Float) {
    val node = findClickableNodeAt(x, y)
    if (node != null && node.isClickable) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)  // fast path
    } else {
        tap(x, y)  // fallback to dispatchGesture
    }
}
```

### Change 11: Mouse Capture via Transparent Overlay (API 29-33)

```kotlin
val overlayView = View(context)
overlayView.setOnGenericMotionListener { _, event ->
    if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
        when (event.action) {
            MotionEvent.ACTION_HOVER_MOVE -> { /* emit MouseMove */ }
            MotionEvent.ACTION_BUTTON_PRESS -> { /* MouseButtonDown */ }
            MotionEvent.ACTION_BUTTON_RELEASE -> { /* MouseButtonUp */ }
            MotionEvent.ACTION_SCROLL -> {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                // emit Scroll
            }
        }
        true
    } else false
}
val params = WindowManager.LayoutParams(
    1, 1,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT
)
windowManager.addView(overlayView, params)
```
Critical: Do NOT use `FLAG_NOT_TOUCHABLE`. Must use `SYSTEM_ALERT_WINDOW` permission.

---

## 4. Input Injection Methods Comparison

| # | Method | Latency | Permissions | Real Mouse | Burst Limit | Our Feasibility |
|---|---|---|---|---|---|---|
| 1 | `AccessibilityService.dispatchGesture()` | 16–100ms | Accessibility | No (virtual) | Serial queue | **Current** |
| 2 | `ACTION_SET_TEXT` | 10–50ms/call | Accessibility | N/A | None | Text only |
| 3 | `performAction()` | 5–20ms | Accessibility | N/A | None | Keys only |
| 4 | `InputManager.injectInputEvent()` | 1–5ms | INJECT_EVENTS or root | Yes (SOURCE_MOUSE) | None | Requires root/Shizuku |
| 5 | `/dev/uinput` | <1ms | Root | Yes (kernel device) | None | Requires root |
| 6 | `sendevent` | 1–5ms | Root/shell | Depends | None | Requires root |
| 7 | `UiAutomation.injectInputEvent()` | 1–5ms | Test context | Yes | None | Not production |
| 8 | `VirtualInputDevice` | <1ms | Signature | Yes (relative) | None | Android 14+ only |
| 9 | **Shizuku + InputManager** | 5–15ms | Shizuku | Yes (SOURCE_MOUSE) | None | **Best no-root** |
| 10 | Clipboard paste | 20–100ms | Accessibility | N/A | None | Fallback |

**Recommendation:** For our unrooted Redmi 9 (API 29): AccessibilityService + `continueStroke()`.
For OnePlus Pad Go (API 33): Consider Shizuku for real mouse cursor.

---

## 5. WiFi/Network Optimizations

| # | Optimization | Impact | Status |
|---|---|---|---|
| 1 | **WifiLock `WIFI_MODE_FULL_LOW_LATENCY`** | Critical — eliminates 10-100ms spikes | **ADD** |
| 2 | **MulticastLock during discovery** | High — broadcast filtered without it | **ADD** |
| 3 | SO_SNDBUF/SO_RCVBUF = 256KB | Medium — burst absorption | ✅ Done |
| 4 | trafficClass = 0x28 (DSCP EF) | Low-Medium — AP may prioritize | ✅ Done |
| 5 | Both devices on 5GHz | Medium — lower baseline | User config |
| 6 | WiFi Direct P2P | **Negative** — worse latency | Correctly avoided |

### WifiLock Implementation
```kotlin
val wifiManager = context.getSystemService(WIFI_SERVICE) as WifiManager
wifiLock = wifiManager.createWifiLock(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
    "InputBridgeLowLatency"
)
wifiLock.acquire()
```

### MulticastLock (discovery only)
```kotlin
val multicastLock = wifiManager.createMulticastLock("InputBridgeDiscovery")
multicastLock.setReferenceCounted(false)
multicastLock.acquire()
// Release after discovery complete
```

---

## 6. Touch Input Optimizations

### Root Causes of Laggy Feel (ranked)
1. **No `requestUnbufferedDispatch()`** — batches to vsync, adds 4-8ms
2. **No historical sample processing** — throws away 50-75% of touch resolution
3. **Bridge-side coroutine overhead** — `scope.launch` per mouse move = 1-2ms
4. **AccessibilityInputFilter VSYNC sync** — adds 4-8ms (unavoidable with AccessibilityService)
5. **dispatchGesture cancels previous** — cancel/dispatch cycle overhead
6. **No motion prediction** — Jetpack MotionEventPredictor not used
7. **Wrong timestamp** — `System.nanoTime()` instead of `MotionEvent.eventTimeNanos`

### 1€ Filter (Best Smoothing)
```kotlin
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,  // Hz — lower = less jitter, more lag
    private val beta: Float = 0.007f,      // higher = less lag at high speed
    private val dCutoff: Float = 1.0f      // Hz — derivative filter cutoff
) {
    fun filter(x: Float, y: Float, timestampNs: Long): Pair<Float, Float> {
        val dt = ((timestampNs - prevTimeNs) / 1_000_000_000f).coerceAtLeast(0.001f)
        val speed = sqrt(dx*dx + dy*dy) / dt
        val cutoff = minCutoff + beta * speed
        val alpha = 1.0f / (1.0f + 1.0f / (2f * PI.toFloat() * cutoff * dt))
        val filteredX = alpha * x + (1f - alpha) * prevFilteredX
        val filteredY = alpha * y + (1f - alpha) * prevFilteredY
        return filteredX to filteredY
    }
}
```
At rest: cutoff ≈ 1 Hz → heavy smoothing. Fast swipe: cutoff ≈ 10+ Hz → nearly zero lag.

### Acceleration Curve (AOSP Piecewise-Linear)
```kotlin
val ACCEL_SEGMENTS = listOf(
    AccelSegment(maxSpeed = 8f,   slope = 1.0f,  intercept = 0f),      // 1:1 precision
    AccelSegment(maxSpeed = 25f,  slope = 1.8f,  intercept = -6.4f),   // slight boost
    AccelSegment(maxSpeed = 80f,  slope = 2.5f,  intercept = -23.9f),  // fast swipes
    AccelSegment(maxSpeed = Float.MAX_VALUE, slope = 3.0f, intercept = -63.9f), // capped
)
// gain = slope + intercept / speed, clamped to [0.5, 4.0]
```

### DPI Scaling
```kotlin
val dpiRatio = phoneDpi.toFloat() / tabletDpi.toFloat()  // ≈ 440/240 = 1.83
scaledDx = rawDx * dpiRatio
```

### Complete Pipeline
```kotlin
// In handleTouch() ACTION_MOVE, single finger:
val rawDx = x - lastX; val rawDy = y - lastY
val sensitivity = prefs.bridgeSensitivity
val (accelDx, accelDy) = computeAcceleration(rawDx, rawDy, sensitivity)
val (filteredDx, filteredDy) = filter.filter(accelDx, accelDy, event.eventTimeNanos)
sendMouseMove(filteredDx, filteredDy, event.eventTimeNanos)
```

---

## 7. Gesture/Accessibility Findings

### dispatchGesture Limits
- `getMaxGestureDuration()` = **60 seconds** (not 2)
- `getMaxStrokeCount()` = **20 strokes** per gesture
- **Only one gesture active at a time** — new call cancels in-progress
- "2-second cooldown" = queue backlog from rapid cancel→restart cycles

### willContinue + continueStroke
- `StrokeDescription(path, startTime, duration, willContinue=true)` keeps finger "down"
- `continueStroke(newPath, 0, newDuration, continueFlag)` picks up where previous ended
- **Critical:** continuation path MUST start at exact endpoint of previous stroke

### Optimal Action Strategy
| Action | Method | Why |
|---|---|---|
| Mouse move | Virtual cursor only (no injection) | Already optimized — cursorX/Y inline |
| Left click | `performAction(ACTION_CLICK)` → fallback `dispatchGesture(tap)` | Bypasses gesture system when possible |
| Right click | `dispatchGesture(longPress)` | No accessibility equivalent |
| Scroll | Single `dispatchGesture(swipe)` | One gesture per scroll is fine |
| Keyboard | `injectKeyCode()` via performAction | Gesture-free |
| Navigation | `performGlobalAction()` | Gesture-free |
| Continuous drag | `willContinue=true` + `continueStroke` state machine | Only way for finger-hold-and-drag |

---

## 8. Reference APK Analysis (io.appground.blek v6.23.1)

### Identity
- Package: `io.appground.blek`, v6.23.1 (code 257)
- Min SDK 23, Target 36, Compile 37
- Kotlin + Jetpack Compose UI
- Native libs: only AndroidX (no native HID code)

### Architecture
```
kk2 (Service + LifecycleObserver)
  └── zx1 (abstract base — BT adapter, notification, sendReport)
        ├── ClassicHidService  (BluetoothHidDevice API)
        └── BleHidService      (BLE GATT server)
```

### HID Reports
- **Keyboard** (Report ID 1): 8 bytes — modifier + 5 key codes
- **Mouse** (Report ID 2): 6 bytes — 5 buttons (L/R/M/Back/Forward) + X + Y + Wheel + Pan
- **Gamepad** (Report ID 6): 4-axis + 16 buttons + hat

### Key Settings
```sql
DeviceConfig (macAddress PRIMARY KEY, mousePointerSpeed, airMouseSpeed, scrollSpeed,
  mouseJiggleMode, layoutScreen, layoutScreenLayoutId, keyboardLanguageLayout,
  lastUsedDate, addedDate)
```

### Touchpad UI
- `PointerPathView` — custom cursor trail (extends ShapeableImageView)
- `ControlItemTouchpadBinding` — layout with touch zone
- `tx2` — touch handler with velocity tracking
- Button layout: Top/Bottom split options
- Settings: invertScrolling, scrollDelta, reverseScrolling, visibleMouseButtons

### Reference APK vs Our Project
| Feature | Reference | InputBridge |
|---|---|---|
| Transport | Classic BT + BLE GATT | UDP over WiFi |
| Inputs | Keyboard + Mouse + Gamepad | Keyboard + Mouse |
| Mouse buttons | 5 (L/R/M/Back/Forward) | L/R only |
| Gamepad | 4-axis + 16 buttons | Not implemented |
| Settings | Room DB | In-memory |
| UI | Compose | Compose (bridge), Views (trackpad) |

---

## 9. Architecture Decisions

### Keep Views for Trackpad, Compose for Bridge Screen

| Factor | View (setOnTouchListener) | Compose (pointerInteropFilter) |
|---|---|---|
| Touch pipeline | MotionEvent → code (1 hop) | MotionEvent → PointerEvent → hit test → filter → code (3-4 hops) |
| Event conversion | Zero overhead | PointerInteropFilter converts internally |
| Per-frame cost | ~0.5ms | Additional composition + layout + draw |
| State triggers | No recomposition | Any mutableStateOf read triggers recomposition |
| Coroutine overhead | None for sync touch | pointerInput uses coroutine suspension |

**Verdict:** Keep MouseTrackpadActivity as pure Views. Keep BridgeScreen as Compose.

### Cursor Overlay: translationX/Y > updateViewLayout

| Tier | Method | Verdict |
|---|---|---|
| 1 | SurfaceControl.Transaction | Best perf, requires reflection — too complex |
| 2 | **translationX/Y + invalidate()** | **Winner** — no Binder, ~10x cheaper |
| 3 | SurfaceView lockHardwareCanvas | Overkill for 32dp cursor |
| 4 | LowLatencyCanvasView | Experimental, drawing apps only |

---

## 10. Priority-Ordered Implementation List

| # | Change | Latency Reduction | Difficulty |
|---|---|---|---|
| 1 | Eliminate `buf.copyOf()` in receive loop | ~1ms + GC | Easy |
| 2 | Reuse DatagramPacket + ByteBuffer | ~0.5ms + GC | Easy |
| 3 | Replace SharedFlow with Channel | ~0.3ms | Medium |
| 4 | Spin-first tryReceive before select{} | ~0.2ms | Easy |
| 5 | `requestUnbufferedDispatch()` | 4-8ms | Easy |
| 6 | Historical sample processing | 2-4ms | Easy |
| 7 | Direct UDP send (no coroutine) | 1-3ms | Medium |
| 8 | WifiLock WIFI_MODE_FULL_LOW_LATENCY | Critical | Easy |
| 9 | willContinue + continueStroke for drag | 5-10ms per move | Medium |
| 10 | Build GestureDescription on IO | ~1ms | Medium |
| 11 | Tune socket buffers (64KB vs 256KB) | ~0.1-1ms | Easy |
| 12 | DatagramChannel NIO non-blocking | ~0.5ms | Hard |
| 13 | Skip DiagnosticsManager on hot path | ~0.1ms | Easy |
| 14 | 1€ filter for smoothing | Better feel | Medium |
| 15 | Velocity acceleration curve | Better feel | Medium |
| 16 | translationX/Y for cursor overlay | ~60 Binder calls/sec saved | Easy |

---

## 11. Known Bugs Still Open

1. **Connection status display never updates** — connectionDot/connectionLabel never updated after creation (dead code)
2. **Taps not registering reliably** — ACTION_UP duration check may be wrong
3. **Cursor overlay uses expensive updateViewLayout** — Binder IPC per frame
4. **No on-screen mouse buttons** — only trackpad, no L/R buttons
5. **High latency from coroutine overhead** — scope.launch per mouse move
6. **No historical sample processing** — 50-75% touch data thrown away
7. **No requestUnbufferedDispatch** — vsync batching adds 4-8ms

---

## 12. Key Invariants

- `Packet.PROTOCOL_VERSION` — never change without coordinating both APKs
- `PacketType` enum ordinal — never reorder or delete
- `when (event: InputEvent)` — never add `else ->`
- `UsbInputCapture.start()` — set `isActive = true` BEFORE launching coroutines
- `DiagnosticsManager.update {}` — only way to write `_state.value`
- `UdpTransport` — set `isConnected = true` before starting coroutines
- `KeyMap.HID_TO_ANDROID` and `HidReportBuilder.ANDROID_TO_HID` — manually maintained inverses
- `AccessibilityService.dispatchGesture()` — only one active at a time; new call cancels in-progress
- `StrokeDescription.continueStroke()` — continuation path MUST start at exact endpoint of previous
- `AccessibilityNodeInfo.performAction(ACTION_CLICK)` — bypasses gesture system entirely
- WifiLock `WIFI_MODE_FULL_LOW_LATENCY` — requires foreground service + screen on + WiFi connected
- 64KB socket buffers for real-time (not 256KB+ which causes bufferbloat)
- `MotionEvent.eventTimeNanos` — kernel timestamp, not `System.nanoTime()`
- `requestUnbufferedDispatch()` — must call before processing, not after
- `getHistoricalX/Y()` — process ALL samples before current to avoid discarding 50-75% of touch data
- `@JvmInline value class` — zero-allocation at JVM level for hot-path data classes
- `@Volatile` — sufficient for simple read/write references; AtomicReference only for CAS
- `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` — for dedicated send thread
- `LAYER_TYPE_HARDWARE` on cursor view — GPU texture cache, only transform matrix updates
