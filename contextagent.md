BOTTLENECK 4: select {} in Send Loop

     File: UdpTransport.kt:222-226

     val bytes = criticalQueue.tryReceive().getOrNull()
         ?: select {
             criticalQueue.onReceive { it }
             inputQueue.onReceive { it }
         }
                                                                                                                       select {} is a coroutine primitive that suspends and resumes — it involves allocating a SelectBuilder and
     registering clauses. For a hot path doing 125+ sends/second, this is measurable.

     Fix: Spin-first, then park

     while (isConnected) {
         // Fast path: try both queues without suspending
         val bytes = criticalQueue.tryReceive().getOrNull()
             ?: inputQueue.tryReceive().getOrNull()
         if (bytes != null) {
             sendPacket(sock, bytes, destination)
             continue
         }
         // Slow path: park until something arrives
         val slowBytes = select {
             criticalQueue.onReceive { it }
             inputQueue.onReceive { it }
         }
         sendPacket(sock, slowBytes, destination)                                                                      }

     This avoids select {} overhead on every iteration when packets are available (the common case at 125 Hz).

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     BOTTLENECK 5: DatagramSocket Buffer Sizes

     File: UdpTransport.kt:127-129

     sock.sendBufferSize = SOCKET_BUFFER_BYTES   // 256 KB
     sock.receiveBufferSize = SOCKET_BUFFER_BYTES // 256 KB                                                       
     Per the gaming optimization research: large buffers add queuing latency. For latency-sensitive UDP,
     smaller buffers are better — they force the sender to not outrun the receiver and reduce kernel-level
     queuing delay.

     Fix:

     // For latency, use smaller buffers (64 KB) to minimize kernel queuing delay
     sock.sendBufferSize = 64 * 1024
     sock.receiveBufferSize = 64 * 1024

     However, the current 256 KB is a reasonable tradeoff for burst absorption. This is tunable based on
     measured jitter.
BOTTLENECK 6: DSCP Traffic Class

     File: UdpTransport.kt:129
                                                                                                                       sock.trafficClass = TRAFFIC_CLASS_LOWDELAY // 0x28

     This sets IP TOS field to DSCP EF (Expedited Forwarding). On a local WiFi network (not going through
     routers that honor DSCP), this has zero effect — the AP and local switch typically strip/ignore DSCP bits.
     But it doesn't hurt, so leave it.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     BOTTLENECK 7: copyOf on Every Receive

     File: UdpTransport.kt:272

     val packet = PacketSerializer.deserialize(buf.copyOf(dp.length)) ?: continue

     buf.copyOf(dp.length) allocates a new byte[] on every received packet. This is the single biggest
     allocation on the receive hot path.

     Fix: Pass length to deserialize and read from the original buffer:
                                                                                                                       val packet = PacketSerializer.deserialize(buf, dp.length) ?: continue

     // In PacketSerializer:
     fun deserialize(data: ByteArray, length: Int): Packet? {
         if (length < Packet.HEADER_SIZE) return null
         val buf = ByteBuffer.wrap(data, 0, length).order(BYTE_ORDER)
         // ... rest unchanged
     }

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     BOTTLENECK 8: InputEvent Data Classes                                                                        
     File: InputEvent.kt

     Every MouseMove(dx, dy) creates a new data class instance with 3 fields (dx, dy, timestampNs). At 125 Hz,
     that's 125 allocations/second just for mouse moves.

     Fix: Use inline/value classes or raw floats on the hot path

     // Option 1: Inline class (zero-allocation at JVM level after optimization)
     @JvmInline
     value class MouseMoveEvent(val packed: Long) {
         val dx: Float get() = Float.fromBits((packed shr 32).toInt())
         val dy: Float get() = Float.fromBits(packed.toInt())
     }
   BOTTLENECK 9: Dispatchers.Main for Accessibility Injection

     File: AccessibilityCommandBus.kt:52-61

     The commandFlow.collect runs on Dispatchers.Main. Every event dispatched to Main incurs a handler post
     latency (~0.5-1ms). This is unavoidable for dispatchGesture() (must be called from main thread), but we
     can minimize the work done on Main.

     Fix: Pre-serialize gesture on IO, dispatch minimal work to Main                                              
     // Build the GestureDescription on IO thread, only call dispatchGesture on Main
     val gesture = withContext(Dispatchers.Default) {
         buildGestureDescription(x, y, ...)
     }
     withContext(Dispatchers.Main) {
         service.dispatchGesture(gesture, null, null)
     }

     GestureDescription.Builder() and Path allocation are CPU work — move them off Main.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     BOTTLENECK 10: AccessibilityService.dispatchGesture() Overhead

     This is the dominant latency on the receiver side. Android's dispatchGesture() goes through:

     1. AccessibilityService → MotionEventInjector (system server Binder call)
     2. System server processes the gesture, generates synthetic MotionEvents                                          3. Events are dispatched to the focused window via InputDispatcher

     Measured latency: 10-30ms per gesture (from NeuralBridge benchmarks).

     There is no way to reduce this below ~5ms without root access or InputManager.injectInputEvent() (requires
     INJECT_EVENTS permission = system app).

     Possible improvement: For mouse cursor movement, instead of dispatchGesture(), use AccessibilityService.
     dispatchGesture() with a continuous pointer hold (willContinue = true) and only update the path. This
     avoids the tap-down/tap-up overhead for each move:

     // On first MouseMove: start a gesture with willContinue=true                                                     // On subsequent MouseMoves: continue the gesture with updated path
     // On MouseButtonUp: release the gesture

     This could reduce per-move latency from ~15ms to ~5ms by avoiding repeated gesture creation/destruction.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     BOTTLENECK 11: DiagnosticsManager.update {} on Hot Path

     File: AccessibilityCommandBus.kt:182-185, ReceiverService.kt:324

     Every event triggers DiagnosticsManager.onPacketReceived() and potentially DiagnosticsManager.update {}.
     The update {} call acquires a lock and allocates a new DiagnosticsData copy.

     Fix: Batch diagnostics updates, skip on hot path

     // On first MouseMove: start a gesture with willContinue=true
     // On subsequent MouseMoves: continue the gesture with updated path
     // On MouseButtonUp: release the gesture

     This could reduce per-move latency from ~15ms to ~5ms by avoiding repeated gesture creation/destruction.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     BOTTLENECK 11: DiagnosticsManager.update {} on Hot Path                                                      
     File: AccessibilityCommandBus.kt:182-185, ReceiverService.kt:324

     Every event triggers DiagnosticsManager.onPacketReceived() and potentially DiagnosticsManager.update {}.
     The update {} call acquires a lock and allocates a new DiagnosticsData copy.

     Fix: Batch diagnostics updates, skip on hot path

     // Instead of updating on every packet:
     // Just increment an atomic counter, flush periodically
     private val packetCount = AtomicLong(0)
     fun onPacketReceived() { packetCount.incrementAndGet() }

     This is already partially done with the counter flush interval. Ensure no DiagnosticsManager.update{} is
     called in the packet-received path.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Summary: Priority-Ordered Optimization List                                                                  
     ┌────┬──────────────────────────────────────────────────────────────────┬────────────────────┬────────────┐
     │#   │Change                                                            │Expected savings    │Difficulty  │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │1   │Eliminate buf.copyOf() in receive loop                            │~1ms + GC pressure  │Easy        │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │2   │Reuse DatagramPacket + ByteBuffer in send/receive                 │~0.5ms + GC         │Easy        │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │3   │Replace SharedFlow with Channel for single-consumer paths         │~0.3ms              │Medium      │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │4   │Spin-first tryReceive before select{} in send loop                │~0.2ms              │Easy        │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤       │5   │Bypass InputEvent for mouse moves (direct USB→serialize→send)     │~1-2ms              │Hard        │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │6   │Use willContinue pointer hold for continuous mouse moves          │~5-10ms per move    │Medium      │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │7   │Build GestureDescription on IO, dispatchGesture on Main           │~1ms                │Medium      │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │8   │Tune socket buffer sizes (64KB for lower queuing latency)         │~0.1-1ms            │Easy        │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │9   │Use DatagramChannel (NIO) non-blocking instead of DatagramSocket  │~0.5ms              │Hard        │
     ├────┼──────────────────────────────────────────────────────────────────┼────────────────────┼────────────┤
     │10  │Skip DiagnosticsManager on hot path                               │~0.1ms              │Easy        │
     └────┴──────────────────────────────────────────────────────────────────┴────────────────────┴────────────┘

     Total estimated improvement: 8-15ms → 3-6ms end-to-end (before dispatchGesture() system overhead, which is
     fixed at ~10ms).

     The hard floor is ~10ms due to Android's dispatchGesture() Binder IPC. To go below that would require
     either root (InputManager.injectInputEvent() with INJECT_INPUT_EVENT_MODE_ASYNC) or a custom kernel input
     device via /dev/uinput.   


next agent 

Research Findings: Touch-to-Cursor Latency Analysis

     The Full Pipeline (Bridge → Receiver → Screen)

     Phone touch → handleTouch() → scope.launch{} → send() → [Channel] → sendLoop → UDP
         ↓ (~0ms)    (~1ms)        (~1-2ms)     (~0.1ms)   (~0ms)     (~0.5ms)

     WiFi → sock.receive() → deserialize → emit → collect → post() → cursorX/Y
       (~1ms)   (~0.5ms)      (~0.3ms)    (~0.1ms) (~0ms*)  (~0.5ms)   (~0ms)

       * MouseMove hot path is inline on IO thread (BUG-042 optimization)
                                                                                                                       Root Causes of Laggy Feel

     1. Bridge-side coroutine overhead (~2-4ms per move)
     MouseTrackpadActivity.kt:380: Each sendMouseMove() launches a new coroutine via scope.launch {
     udpTransport?.send(packet) }. At 120Hz touch, that's 120 coroutine dispatches/second, each with ~1-2ms of
     Dispatchers.IO overhead. The packet then enters a Channel and waits for the send loop to pick it up.

     2. No historical sample processing (biggest gain)
     MouseTrackpadActivity.kt:319: Only event.getX(0) / event.getY(0) are used. Android batches multiple touch
     samples per MotionEvent (the kernel touch driver typically runs at 120-240Hz, but ACTION_MOVE events are
     batched to vsync at 60-120Hz). Each batched event contains 1-4 historical samples via getHistoricalX()/
     getHistoricalY() that are completely ignored. This means we're throwing away 50-75% of the touch
     resolution.

     3. No unbuffered dispatch
     MouseTrackpadActivity.kt:237: The trackpad view doesn't call requestUnbufferedDispatch(). By default,
     Android batches ACTION_MOVE events to vsync, adding up to 8ms of latency. The docs explicitly say:
     "requestUnbufferedDispatch asks the input system not to batch MotionEvents but instead deliver them as
     soon as they're available".                                                                                  
     4. AccessibilityInputFilter VSYNC sync
     When an AccessibilityService is running, AccessibilityInputFilter intercepts all input events and syncs
     them to vsync. The InputDispatcher thread follows vsync rhythm instead of InputReader rhythm. This adds ~4-
     8ms of additional latency because events are held until the next vsync instead of being dispatched
     immediately.

     5. dispatchGesture cancels previous gestures
     InputBridgeAccessibilityService.kt:132: dispatchGesture(gesture, null, null) — every call cancels any in-
     progress gesture. For cursor movement, this means rapid updates create a cancel/dispatch cycle with IPC
     overhead each time.
                                                                                                                       6. No motion prediction
     The Jetpack MotionEventPredictor library can predict the next touch position using Kalman filtering.
     Without it, we always render where the finger was, not where it will be.

     7. System.nanoTime() vs MotionEvent.getEventTimeNanos()
     InputEvent.kt:30: timestampNs: Long = System.nanoTime() captures the time when the InputEvent object is
     created (after coroutine scheduling), not when the touch actually happened. MotionEvent.getEventTimeNanos()
      gives the kernel timestamp with nanosecond precision.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Specific Code Changes for Smoothest Movement

     Change 1: Unbuffered dispatch + historical sample processing (Bridge side)

     File: MouseTrackpadActivity.kt

     // In onCreate(), after trackpadView setup:
     trackpadView.setOnTouchListener { v, event ->
       v.requestUnbufferedDispatch(event)
         }
         handleTouch(event)
         true
     }

     In handleTouch(), replace the ACTION_MOVE block with historical sample processing:

     MotionEvent.ACTION_MOVE -> {
         if (isTwoFingerScroll && pointerCount >= 2) {                                                                         val currentY = event.getY(1)
             val deltaY = currentY - lastScrollY
             lastScrollY = currentY
             if (kotlin.math.abs(deltaY) > 1f) {
                 sendScroll(deltaY / 10f)
             }
         } else if (pointerCount == 1) {
             val sensitivity = prefs.bridgeSensitivity

             // Process ALL historical samples in the batch for maximum resolution
             val historySize = event.historySize
             for (h in 0 until historySize) {
                 val hx = event.getHistoricalX(0, h)
                 val hy = event.getHistoricalY(0, h)
                 val hdx = hx - lastX
                 val hdy = hy - lastY
                 totalMovement += kotlin.math.sqrt(hdx * hdx + hdy * hdy)

                 if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {                                                                isDragging = true
                     handler.removeCallbacks(longPressRunnable)
                     statusText.text = "Dragging..."
                 }
                 if (isDragging) {
                     sendMouseMove(hdx * sensitivity, hdy * sensitivity)
                 }
                 lastX = hx
                 lastY = hy
             }

             // Process current sample                                                                                         val x = event.getX(0)
             val y = event.getY(0)
             val dx = x - lastX
             val dy = y - lastY
             totalMovement += kotlin.math.sqrt(dx * dx + dy * dy)

             if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {
                 isDragging = true
                 handler.removeCallbacks(longPressRunnable)
                 statusText.text = "Dragging..."
             }
             if (isDragging) {
                 sendMouseMove(dx * sensitivity, dy * sensitivity)
             }
             lastX = x
             lastY = y
         }
     }

     Change 2: Direct UDP send for mouse moves (eliminate coroutine overhead) File: MouseTrackpadActivity.kt — replace sendMouseMove and add a direct-send buffer:

     // Pre-allocated buffer for mouse move hot path — zero coroutine overhead
     @Volatile private var cachedPacket: com.inputbridge.protocol.Packet? = null

     private fun sendMouseMove(dx: Float, dy: Float) {
         val event = InputEvent.MouseMove(dx = dx, dy = dy)
         val packet = packetFactory.fromEvent(event) ?: return
         val transport = udpTransport ?: return
         // Direct synchronous send — no coroutine dispatch overhead
         // UDP send on localhost is ~0.1ms, well under 16ms frame budget
         scope.launch(Dispatchers.IO) {
             transport.sendDirect(packet)
         }                                                                                                             }

     File: UdpTransport.kt — add a direct-send method that bypasses the channel:

     /**
      * Direct send for hot-path mouse moves — bypasses the channel + send loop
      * for minimum latency. Only safe for fire-and-forget input packets.
      */
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
             val dp = DatagramPacket(bytes, bytes.size, destination)                                                           sock.send(dp)
             true
         } catch (e: Exception) {
             if (isConnected) BridgeLogger.w(TAG, "Direct send error", e)
             false
         }
     }

     Change 3: Use kernel timestamp instead of System.nanoTime()

     File: MouseTrackpadActivity.kt — capture the event time at the touch point:
                                                                                                                       private fun sendMouseMove(dx: Float, dy: Float, timestampNs: Long) {
         val event = InputEvent.MouseMove(dx = dx, dy = dy, timestampNs = timestampNs)
         // ...
     }

     In handleTouch(), pass the timestamp:

     if (isDragging) {
         sendMouseMove(dx * sensitivity, dy * sensitivity, event.eventTimeNanos)
     }

     This ensures the receiver knows when the touch actually happened, enabling interpolation on the receiver
     side.

     Change 4: Cursor smoothing with exponential moving average (Receiver side)

     File: AccessibilityCommandBus.kt — add cursor smoothing for the overlay:

 // ── Cursor smoothing ──────────────────────────────────────────────────────
     // Exponential moving average for smooth visual cursor movement.
     // The raw cursor position updates instantly for injection accuracy;
     // the smoothed position is used by the overlay for visual smoothness.
     @Volatile private var smoothCursorX = 0f
     @Volatile private var smoothCursorY = 0f
     private const val SMOOTHING_FACTOR = 0.4f  // 0=instant, 1=infinite lag
                                                                                                                       // In post(), after updating cursorX/cursorY for MouseMove:
     if (event is InputEvent.MouseMove) {
         // Smooth the visual position
         smoothCursorX += (cursorX - smoothCursorX) * SMOOTHING_FACTOR
         smoothCursorY += (cursorY - smoothCursorY) * SMOOTHING_FACTOR
         _cursorPosition.value = Pair(smoothCursorX, smoothCursorY)
     }

     Change 5: Velocity-based acceleration curve (Windows-style ballistics)

     File: MouseTrackpadActivity.kt — add acceleration:

     // ── Velocity tracking for acceleration curve ──────────────────────────────
     private var lastMoveTimeNs = 0L
     private var velocityX = 0f
     private var velocityY = 0f
     private const val VELOCITY_SMOOTHING = 0.3f
     private const val ACCEL_THRESHOLD_LOW = 200f   // px/s — below this, 1:1
     private const val ACCEL_THRESHOLD_HIGH = 2000f  // px/s — above this, max accel                                   private const val ACCEL_MAX = 3.0f              // maximum multiplier

     private fun computeAcceleration(dx: Float, dy: Float, nowNs: Long): Pair<Float, Float> {
         if (lastMoveTimeNs == 0L) {
             lastMoveTimeNs = nowNs
             return Pair(dx, dy)
         }
         val dtSec = (nowNs - lastMoveTimeNs) / 1_000_000_000f
         lastMoveTimeNs = nowNs
         if (dtSec <= 0f || dtSec > 0.1f) return Pair(dx, dy)

         // Smoothed velocity in px/s                                                                                      val rawVx = dx / dtSec
         val rawVy = dy / dtSec
         velocityX += (rawVx - velocityX) * VELOCITY_SMOOTHING
         velocityY += (rawVy - velocityY) * VELOCITY_SMOOTHING

         // Vector magnitude for symmetric acceleration (Windows approach)
         val speed = kotlin.math.sqrt(velocityX * velocityX + velocityY * velocityY)
         val accel = when {
             speed < ACCEL_THRESHOLD_LOW -> 1.0f
             speed > ACCEL_THRESHOLD_HIGH -> ACCEL_MAX
             else -> 1.0f + (ACCEL_MAX - 1.0f) *
                 (speed - ACCEL_THRESHOLD_LOW) / (ACCEL_THRESHOLD_HIGH - ACCEL_THRESHOLD_LOW)
         }

         return Pair(dx * accel, dy * accel)
     }

     Then in handleTouch() ACTION_MOVE, replace the raw delta:

     if (isDragging) {
         val baseSensitivity = prefs.bridgeSensitivity
   sendMouseMove(ax, ay, event.eventTimeNanos)
     }

     Change 6: Gesture continuation for click-drag (Receiver side)

     File: InputBridgeAccessibilityService.kt — add continued gesture support for drag:
                                                                                                                       private var currentStroke: GestureDescription.StrokeDescription? = null
     private var isGestureActive = false

     /**
      * Start a continuous drag gesture. Subsequent calls to continueDrag()
      * extend the stroke without cancelling it.
      */
     fun startDrag(x: Float, y: Float) {
         val path = Path().apply { moveTo(x, y) }
         currentStroke = GestureDescription.StrokeDescription(path, 0, 16, true) // willContinue=true
         val gesture = GestureDescription.Builder()
             .addStroke(currentStroke!!)
             .build()
         isGestureActive = true
         dispatchGesture(gesture, null, null)
     }

     fun continueDrag(x: Float, y: Float) {
         val stroke = currentStroke ?: return                                                                              val path = Path().apply {
             moveTo(x - 1f, y)  // start from near current position
             lineTo(x, y)
         }
         currentStroke = stroke.continueStroke(path, 0, 16, true)
         val gesture = GestureDescription.Builder()
             .addStroke(currentStroke!!)
             .build()
         dispatchGesture(gesture, null, null)
     }

     fun endDrag(x: Float, y: Float) {                                                                                     val stroke = currentStroke ?: return
         val path = Path().apply {
             moveTo(x - 1f, y)
             lineTo(x, y)
         }
         currentStroke = stroke.continueStroke(path, 0, 16, false) // willContinue=false
         val gesture = GestureDescription.Builder()
             .addStroke(currentStroke!!)
             .build()
         isGestureActive = false
         dispatchGesture(gesture, null, null)
     }

     Change 7: Reduce coroutine dispatch overhead on receiver hot path

     File: AccessibilityCommandBus.kt — the MouseMove path is already optimized (inline on IO thread). But the
     commandFlow collector runs on Dispatchers.Main which adds latency for clicks. Add a dedicated low-latency
     dispatcher:

     // Replace the Main dispatcher with a single-thread dispatcher
     // for the command flow collector to avoid contention with UI rendering


     File: AccessibilityCommandBus.kt — the MouseMove path is already optimized (inline on IO thread). But the
     commandFlow collector runs on Dispatchers.Main which adds latency for clicks. Add a dedicated low-latency
     dispatcher:

     // Replace the Main dispatcher with a single-thread dispatcher
     // for the command flow collector to avoid contention with UI rendering
     private val injectionDispatcher = Dispatchers.Main.limitedParallelism(1)
     private val injectionScope = CoroutineScope(injectionDispatcher + SupervisorJob())

     init {
         injectionScope.launch {
             commandFlow.collect { event ->
                 // ... existing handleEvent logic
             }
         }
     }

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────  
     Summary: Impact ranking

     ┌───────────────────────────────────┬────────────────────────────────────────────────┬────────────────────┐
     │Change                             │Estimated Latency Reduction                     │Complexity          │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤
     │1. Historical samples              │2-4ms (doubles touch resolution)                │Low                 │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤
     │2. Unbuffered dispatch             │4-8ms (eliminates vsync batching)               │Low                 │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤
     │3. Direct UDP send                 │1-3ms (eliminates coroutine overhead)           │Medium              │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤       │4. Kernel timestamps               │0ms latency but enables interpolation           │Low                 │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤
     │5. Velocity acceleration           │Better feel not less latency                    │Medium              │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤
     │6. Gesture continuation            │Eliminates cancel/dispatch cycle                │High                │
     ├───────────────────────────────────┼────────────────────────────────────────────────┼────────────────────┤
     │7. Dedicated dispatcher            │0.5-1ms                                         │Low                 │
     └───────────────────────────────────┴────────────────────────────────────────────────┴────────────────────┘

     The single highest-impact change is #2 (unbuffered dispatch) + #1 (historical samples). Together they
     reduce the bridge-side touch-to-packet latency from ~8-16ms to ~1-2ms. Combined with #3 (direct UDP send),
     the total phone-to-tablet pipeline becomes sub-5ms on a local WiFi network, which is competitive with BLE
     HID's 7.5ms minimum connection interval.

     The reference APK (Bluetooth Keyboard & Mouse) uses BLE HID which gets kernel-level input injection
     through the HID subsystem — it bypasses dispatchGesture entirely and gets a real hardware cursor. Our
     WiFi+accessibility approach will always have the dispatchGesture overhead for clicks, but for cursor
     movement the hot path (inline cursorX/cursorY update + StateFlow) is already near-optimal. The key wins
     are on the bridge side where we're currently wasting touch resolution and adding unnecessary latency.



next agent 

The updatePosition correctly uses CursorArrowView.HOTSPOT_INSET_DP (now 3) to offset the view so the arrow
     tip lands exactly at the mouse position. The WindowManager.updateViewLayout() call is direct on the Main
     thread — no Choreographer or postDelayed needed.

     Here's a summary of what changed in the CursorArrowView rewrite:

     ┌─────────────┬─────────────────────────────────────────────────┬─────────────────────────────────────────┐
     │Aspect       │Before                                           │After                                    │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤
     │Path         │Recomputed every onDraw frame                    │Pre-computed once in onSizeChanged,      │
     │computation  │                                                 │reused                                   │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤       │Shadow       │path.offset(dx,dy) mutates original, then offset(│path.offset(dx,dy,shadowPath) — never    │
     │rendering    │-dx,-dy) to undo                                 │mutates original                         │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤
     │Arrow shape  │Approximate Windows-like shape                   │Exact Windows 10/11 32x32 bitmap         │
     │             │                                                 │coordinates                              │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤
     │HOTSPOT_INSET│2dp (clips shadow at tip)                        │3dp (contains shadow + stroke)           │
     │_DP          │                                                 │                                         │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤
     │Stroke width │1.2dp                                            │1.5dp (crisper at all sizes)             │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤
     │Stroke cap   │Not set                                          │ROUND (smoother tail tip)                │
     ├─────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────┤
     │Shadow alpha │80/255                                           │70/255 (subtler, less visual noise)      │
     └─────────────┴─────────────────────────────────────────────────┴─────────────────────────────────────────┘

     No-lag positioning is already handled correctly: AccessibilityCommandBus.cursorPosition (a
     MutableStateFlow) is collected on Dispatchers.Main, which calls WindowManager.updateViewLayout() directly
     — this bypasses Choreographer entirely and updates on the very next frame.
next agent 

Here's the comprehensive comparison based on the research and your current codebase:

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────  
     Android Input Injection Methods — Full Comparison

     ┌──┬──────────────┬───────────────┬────────────────┬──────────┬───────┬──────────────────────┬────────────┐
     │# │Method        │Latency        │Permissions     │Real      │Secure │Burst Limit           │Our         │
     │  │              │               │Required        │Mouse     │Window │                      │Feasibility │
     │  │              │               │                │Cursor    │       │                      │            │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │1 │AccessibilityS│16–100ms (     │User grants     │No (      │Blocked│Serial queue;         │Current     │
     │  │ervice.dispatc│display        │Accessibility   │virtual   │       │gestures are          │method      │
     │  │hGesture()    │refresh rate   │                │cursor    │       │serialized, rapid     │            │
     │  │              │sampling)      │                │overlay)  │       │dispatches queue up   │            │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │2 │AccessibilityS│10–50ms per    │Accessibility   │N/A       │Blocked│None (per-node)       │Currently   │
     │  │ervice.ACTION_│call           │                │          │       │                      │used for    │
     │  │SET_TEXT      │               │                │          │       │                      │text        │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │3 │AccessibilityS│5–20ms         │Accessibility   │N/A       │Partial│None                  │Currently   │
     │  │ervice.perform│               │                │          │ly bloc│                      │used for    │       │  │Action()      │               │                │          │ked    │                      │keys        │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │4 │InputManager. │1–5ms          │INJECT_EVENTS ( │Yes (     │Yes (  │None (async mode)     │Requires    │
     │  │injectInputEve│               │signature) or   │SOURCE_MOU│all    │                      │root or     │
     │  │nt()          │               │root via Shizuku│SE)       │windows│                      │Shizuku     │
     │  │              │               │                │          │)      │                      │            │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │5 │/dev/uinput   │<1ms (kernel   │Root (write to /│Yes (real │Yes    │None                  │Requires    │
     │  │virtual device│event queue)   │dev/uinput)     │kernel    │       │                      │root        │
     │  │              │               │                │device)   │       │                      │            │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │6 │sendevent → / │1–5ms (batched │Root or shell   │Depends   │Yes    │None                  │Requires    │       │  │dev/input/    │binary)        │SELinux context │on device │       │                      │root        │
     │  │eventX        │               │                │          │       │                      │            │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │7 │UiAutomation. │1–5ms          │Test            │Yes       │Yes    │None                  │Not usable  │
     │  │injectInputEve│               │instrumentation │          │       │                      │in          │
     │  │nt()          │               │context         │          │       │                      │production  │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │8 │VirtualInputDe│<1ms (         │CREATE_VIRTUAL_D│Yes (     │Yes    │None                  │Android 14+ │
     │  │vice (VirtualM│SystemApi)     │EVICE (signature│relative) │       │                      │only,       │
     │  │ouse)         │               │)               │          │       │                      │signature   │
     │  │              │               │                │          │       │                      │perm        │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │9 │Shizuku +     │5–15ms (IPC    │Shizuku (ADB    │Yes (     │Yes    │None                  │Best no-    │
     │  │InputManager  │overhead)      │shell bridge)   │SOURCE_MOU│       │                      │root option │
     │  │              │               │                │SE)       │       │                      │            │
     ├──┼──────────────┼───────────────┼────────────────┼──────────┼───────┼──────────────────────┼────────────┤
     │10│Clipboard     │20–100ms       │Accessibility   │N/A       │Blocked│None                  │Currently   │
     │  │paste (       │               │                │          │       │                      │used as     │
     │  │ACTION_PASTE) │               │                │          │       │                      │fallback    │
     └──┴──────────────┴───────────────┴────────────────┴──────────┴───────┴──────────────────────┴────────────┘

Key Findings

     Your current approach — dispatchGesture() — has these hard limits:

     - Path sampling: On API ≥29, gestures are sampled at display refresh rate (~16ms on 60Hz, ~11ms on 90Hz).
       On older APIs, fixed 100ms.
     - Serialization: Only one gesture stream at a time. Real user touch cancels your gesture.
     - No real mouse: It synthesizes touch events, not pointer events. No hover, no right-click, no scroll
       wheel.
     - No secure window: Cannot inject into lockscreen, banking apps, etc.

     The "2-second burst limit" you mention is likely the gesture queuing behavior — dispatchGesture() calls           are serialized internally by the accessibility framework. Rapid successive calls queue up rather than
     execute immediately, creating effective throughput limits around 10–20 gestures/second.

     The best path for lower latency:

     ┌────────────────┬────────────────────────────────────────────────────────────────────────────────────────┐
     │Scenario        │Recommendation                                                                          │
     ├────────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
     │Rooted device   │Use /dev/uinput to create a VirtualMouse kernel device. Sub-millisecond latency. Real   │
     │                │cursor. Works everywhere. Android has a built-in uinput command.                        │
     ├────────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
     │No root, device │InputManager.injectInputEvent() with INJECT_EVENTS (requires signing with platform key  │
     │owner           │— not practical for third-party).                                                       │
     ├────────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
     │No root, no     │Stay with AccessibilityService. Optimize by using continueStroke() for drag sequences   │
     │Shizuku         │and reducing gesture duration.                                                          │
     ├────────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
     │No root, with   │Use Shizuku to call InputManager.injectInputEvent() via ADB shell. ~5–15ms latency.     │
     │Shizuku         │Real mouse pointer via SOURCE_MOUSE. Best no-root option.                               │       ├────────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
     │Android 14+     │Use VirtualInputDevice/VirtualMouse SystemApi. Sub-ms latency. Proper relative mouse.   │
     │with system app │                                                                                        │
     └────────────────┴────────────────────────────────────────────────────────────────────────────────────────┘

     Keyboard-Specific Comparison

     ┌─────────────────────────────────────────┬──────────┬────────────────────────────────────────────────────┐
     │Method                                   │Latency   │Notes                                               │
     ├─────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────┤
     │AccessibilityService.injectKeyCode() (   │5–20ms    │Via performAction() on focused node; works for text │
     │our current)                             │          │fields only                                         │       ├─────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────┤
     │InputManager.injectInputEvent() with     │1–3ms     │System-wide; works in games, secure fields          │
     │KeyEvent                                 │          │                                                    │
     ├─────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────┤
     │uinput virtual keyboard                  │<1ms      │System-wide; real HID keyboard device               │
     ├─────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────┤
     │Character-by-character via               │10–50ms/  │Our current approach for text; slow for bulk text   │
     │ACTION_SET_TEXT                          │char      │                                                    │
     └─────────────────────────────────────────┴──────────┴────────────────────────────────────────────────────┘

     Recommendation for InputBridge

     Given that the receiver app runs on a OnePlus Pad Go (API 33+) which likely supports Shizuku:

     1. Primary path (no root): Implement Shizuku + InputManager.injectInputEvent(). This gives you
        SOURCE_MOUSE for a real cursor, 1–5ms latency, and works on secure windows. Shizuku is already used by
        apps like Input-Leaf for exactly this use case.

     2. Accessibility fallback: Keep the current AccessibilityService for devices without Shizuku, but use
        continueStroke() for smoother drag/scroll sequences instead of dispatching separate gestures for each
        position update.

3. Root path (if available): The Android uinput system command can create a virtual mouse device with <1ms
        kernel-level latency. Your codebase already has transport-bluetooth-hid which demonstrates HID
        descriptor knowledge — the same approach applies to uinput virtual devices.
  next agent 

The Answer: Hybrid Dedicated-Thread + Lock-Free Ring Buffer

     At 1000Hz, you have a 1ms budget. The DatagramSocket.send() alone costs ~5-15μs. The coroutine overhead
     adds ~1-2μs on top. You can't afford coroutine dispatch on the hot path.

     Recommended architecture:

     1. Dedicated Send Thread (NOT a coroutine)

     // One thread, pinned to a CPU core, never suspends
     private val sendThread = Thread({
         Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)                                                   while (running) {
             val packet = ringBuffer.take()  // lock-free SPSC
             val bytes = preSerializedCache[packet.seqNo % CACHE_SIZE]
             sock.send(DatagramPacket(bytes, bytes.length, dest))
         }
     }, "input-bridge-send").apply { start() }

     Why: A dedicated thread avoids:

     - Coroutine state machine allocation (140ns + 88B per launch)
     - Dispatcher queue contention (IO pool is shared with 64 other threads)
     - select {} overhead (~200-400ns per iteration)
     - Continuation object creation and resume overhead

     Thread.start() costs ~200μs BUT you only start it once. Per-event cost is zero.

     2. Lock-Free SPSC Ring Buffer (replace Channel)

     // Single-Producer Single-Consumer ring buffer                                                                    // Producer: USB capture thread (or MouseTrackpadActivity touch handler)
     // Consumer: dedicated send thread
     private val ringBuffer = SPSCRingBuffer<ByteArray>(256)

     Why: Channel(64) adds ~70ns per trySend + ~70ns per tryReceive + select{} overhead. A SPSC ring buffer
     with @Volatile head/tail indices costs 5-10ns for both put and take on the same cache line. At 1000 events/
     sec, this saves 130ns/event — not huge, but zero-allocation.

     3. Pre-Serialized Packet Cache (eliminate per-event ByteBuffer allocation)

     The current PacketSerializer.serialize() allocates a new ByteBuffer per mouse move: ByteBuffer.allocate(20)
      in serialize() + ByteBuffer.allocate(8) in buildMouseMovePayload(). That's 2 allocations + 2 GC-tracked          objects per 1ms event.

     Solution: Pre-serialize all mouse-move packets into a cache at startup:

     // Pre-computed byte arrays for the most common case:
     // MOUSE_MOVE with all possible dx/dy values (quantized to 0.1f steps)
     // OR: pre-allocate a single reusable ByteArray and write directly
     private val sendBuffer = ByteArray(20) // HEADER_SIZE + 8 payload

     fun serializeMouseMoveInto(dx: Float, dy: Float): ByteArray {
         // Write directly into pre-allocated buffer — zero allocation
         sendBuffer[0] = Packet.PROTOCOL_VERSION
         sendBuffer[1] = PacketType.MOUSE_MOVE.id
         // ... write seq, timestamp, dx, dy directly via ByteBuffer.wrap(sendBuffer)
         return sendBuffer // caller must send before next call (single-threaded sender)
     }

     This is safe because the dedicated send thread is the only consumer.
 4. Avoid ALL Suspend Calls in the Send Path

     The current UdpTransport.send() is suspend fun. Even though it uses trySend (which doesn't suspend), the
     suspend modifier adds the Continuation state machine overhead to every caller.

     Fix: Split into:

     - sendNonBlocking(packet: Packet): Boolean — called from hot path, never suspends
     - sendCritical(packet: Packet) — for PING/PONG/DISCONNECT, can use coroutine

     5. SharedFlow vs StateFlow vs Channel for Events

     ┌───────────────────────────────┬──────────────────────┬──────────────────────┬───────────────────────────┐       │Primitive                      │Emit cost             │Collect cost          │Best for                   │
     ├───────────────────────────────┼──────────────────────┼──────────────────────┼───────────────────────────┤
     │MutableSharedFlow(replay=0,    │~100-200ns (lock + O( │~100ns                │Current architecture (OK)  │
     │extraBufferCapacity=64)        │N) subscribers)       │                      │                           │
     ├───────────────────────────────┼──────────────────────┼──────────────────────┼───────────────────────────┤
     │Channel(64)                    │~70ns trySend         │~70ns tryReceive +    │Better than SharedFlow for │
     │                               │                      │200-400ns select      │1:1                        │
     ├───────────────────────────────┼──────────────────────┼──────────────────────┼───────────────────────────┤
     │Lock-free ring buffer          │~5-10ns               │~5-10ns               │Best for dedicated thread  │
     │                               │                      │                      │pair                       │
     ├───────────────────────────────┼──────────────────────┼──────────────────────┼───────────────────────────┤
     │MutableStateFlow               │conflate (drops       │O(N) per update       │Never for events — drops   │
     │                               │intermediate)         │                      │intermediate moves         │
     └───────────────────────────────┴──────────────────────┴──────────────────────┴───────────────────────────┘

     Verdict: For the hot path, replace both SharedFlow and Channel with a SPSC ring buffer. Keep SharedFlow
     only for diagnostic/metadata events that don't need sub-ms latency.

     6. @Volatile vs AtomicReference                                                                              
     For simple read/write of a reference (like udpTransport): @Volatile is faster. It compiles to a single
     volatile field access (~5-10ns). AtomicReference adds 16B object header overhead and an extra pointer
     indirection. Use AtomicReference only when you need compareAndSet.

     In the current code, @Volatile private var udpTransport: UdpTransport? is already correct.

     Concrete Changes for MouseTrackpadActivity

     The current code at MouseTrackpadActivity.kt:377-381:

     private fun sendMouseMove(dx: Float, dy: Float) {                                                                     val event = InputEvent.MouseMove(dx = dx, dy = dy)
         val packet = packetFactory.fromEvent(event) ?: return
         scope.launch { udpTransport?.send(packet) }  // NEW COROUTINE PER EVENT!
     }

     This launches a new coroutine for every mouse move. At 1000Hz, that's 1000 coroutine launches/sec × 140ns
     = 140μs/sec overhead + 88B × 1000 = 88KB/sec GC pressure.

     Fix:

     private fun sendMouseMove(dx: Float, dy: Float) {
         val bytes = preSerializedMouseMove(dx, dy) ?: return
         sendRingBuffer.put(bytes)  // lock-free, ~5ns
         // Dedicated send thread picks it up — no coroutine needed
     }

     Final Architecture Diagram

     USB HID → UsbInputCapture.read() [IO thread]
       → SharedFlow.emit(event)
         → captureJob.collect [IO thread]
  → SharedFlow.emit(event)
         → captureJob.collect [IO thread]
           → serialize into pre-allocated buffer   [IO thread — zero alloc]
             → ringBuffer.put(bytes)               [IO thread — lock-free]
               → sendThread.take()                 [dedicated thread — zero suspend]
                 → DatagramSocket.send()           [kernel — ~5-15μs]

     MouseTrackpadActivity touch → handleTouch() [Main thread]
       → serialize into pre-allocated buffer      [Main thread — zero alloc]
         → ringBuffer.put(bytes)                  [Main thread — lock-free]
           → (same sendThread as above)

     Total per-event overhead (excluding kernel send): ~10-20ns (ring buffer put/take) + ~30-50ns (serialize
     into pre-allocated buffer) = ~40-70ns vs current ~1.5-2.5μs. That's a 25-60x reduction in userspace
     overhead.

     Priority-Ordered Implementation List

     1. Eliminate scope.launch {} per mouse event in MouseTrackpadActivity — biggest single win
     2. Pre-allocate send buffers in PacketSerializer — eliminate ByteBuffer allocation on hot path


next agent 
Deep Analysis: Touchpad Implementation in Reference APK

     1. Touchpad UI Architecture

     Core Classes Found:

     - Touchpad — Main touchpad view component
     - PointerPathView — Custom view for drawing cursor trails
     - ControlItemTouchpadBinding — Layout binding for touchpad control items
                                                                                                                       Touch Event Flow:

     - onTouch → onTouchEvent → setOnTouchListener pattern
     - Uses ViewTreeObserver.OnTouchModeChangeListener for fullscreen touch mode detection

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     2. Device Configuration (Room Database)

     The APK uses Room database (not SharedPreferences) for persistent settings:

     CREATE TABLE IF NOT EXISTS `DeviceConfig` (
         macAddress TEXT NOT NULL PRIMARY KEY,
         mousePointerSpeed INTEGER NOT NULL,
         airMouseSpeed INTEGER NOT NULL,
         scrollSpeed INTEGER NOT NULL,
         mouseJiggleMode TEXT NOT NULL DEFAULT 'disabled',
         layoutScreen INTEGER NOT NULL,
         layoutScreenLayoutId INTEGER NOT NULL,                                                                            keyboardLanguageLayout TEXT NOT NULL,
         lastUsedDate INTEGER NOT NULL,
         addedDate INTEGER NOT NULL
     )

     Key Settings:

     ┌──────────────────────────────┬───────────────────┬──────────────────────────────────────────────────────┐
     │Setting                       │Type               │Purpose                                               │
     ├──────────────────────────────┼───────────────────┼──────────────────────────────────────────────────────┤
     │mousePointerSpeed             │INTEGER            │Cursor movement speed                                 │
     ├──────────────────────────────┼───────────────────┼──────────────────────────────────────────────────────┤       │airMouseSpeed                 │INTEGER            │Air mouse sensitivity                                 │
     ├──────────────────────────────┼───────────────────┼──────────────────────────────────────────────────────┤
     │scrollSpeed                   │INTEGER            │Scroll wheel speed                                    │
     ├──────────────────────────────┼───────────────────┼──────────────────────────────────────────────────────┤
     │mouseJiggleMode               │TEXT               │Mouse jiggle feature (disabled by default)            │
     └──────────────────────────────┴───────────────────┴──────────────────────────────────────────────────────┘

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     3. Mouse Button System

     Classes:

     - MouseButton — Button state representation
     - MouseButtonClick — Click event handling

     UI Elements:

     - visibleMouseButtons — Number of visible buttons
     - showMouseButtonsTop / showMouseButtonsBottom — Button layout positions
     - show_mouse_buttons — Preference key
4. Scroll Configuration

     Found Settings:

     invertScrolling = false    // Default: natural scrolling off
     scrollDelta = ...          // Scroll amount per event
     reverseScrolling = false   // Default: normal scroll direction

     Classes:

     - MouseWheelScrollDelta — Scroll delta value wrapper                                                         
     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     5. Touch Modes

     Fullscreen Mode:

     - startFullScreen — Launch in fullscreen
     - ToggleFullscreen — Toggle action
     - fullscreen_drag_msg — Drag mode message (implies drag-to-move functionality)
     - isInTouchMode() — Android system check

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     6. Velocity & Physics

     Tracked Values:

     - velocityCoefficient — Speed multiplier                                                                          - airMouseSpeed — Air mouse sensitivity
     - density — Screen density for pixel calculations
     - Uses Android VelocityTracker for fling detection

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     7. Key Differences from Our Implementation

     ┌──────────────────────────────┬────────────────────────────────────────┬─────────────────────────────────┐
     │Aspect                        │Reference APK                           │Our InputBridge                  │
     ├──────────────────────────────┼────────────────────────────────────────┼─────────────────────────────────┤
     │Storage                       │Room Database                           │SharedPreferences                │       ├──────────────────────────────┼────────────────────────────────────────┼─────────────────────────────────┤
     │Scroll invert                 │invertScrolling boolean                 │Not implemented                  │
     ├──────────────────────────────┼────────────────────────────────────────┼─────────────────────────────────┤
     │Air mouse                     │Dedicated airMouseSpeed                 │Not implemented                  │
     ├──────────────────────────────┼────────────────────────────────────────┼─────────────────────────────────┤
     │Mouse jiggle                  │mouseJiggleMode                         │Not implemented                  │
     ├──────────────────────────────┼────────────────────────────────────────┼─────────────────────────────────┤
     │Button layout                 │Top/Bottom split                        │Single row                       │
     ├──────────────────────────────┼────────────────────────────────────────┼─────────────────────────────────┤
     │Pointer trail                 │PointerPathView                         │Not implemented                  │
     └──────────────────────────────┴────────────────────────────────────────┴─────────────────────────────────┘

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     8. Strings Output (Complete)

     Command 1 - Touchpad:

      in onTouchEvent
     #Landroid/view/View$OnTouchListener;
     )Lio/appground/blek/utils/PointerPathView;
     )Lio/appground/blek/utils/PointerPathView;
     , enabledTouchClick=
     9Landroid/view/ViewTreeObserver$OnTouchModeChangeListener;
     AndroidOwner:onTouch
     Touchpad
     addOnTouchModeChangeListener
     getCloseIconTouchBounds
     getCloseIconTouchBoundsInt
     onTouch                                                                                                           onTouchEvent
     onTouchExplorationStateChanged
     onTouchModeChanged
     removeOnTouchModeChangeListener
     setCanceledOnTouchOutside
     setOnTouchListener
     yinflate(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Z)Lio/appground/blek/databinding/
     ControlItemTouchpadBinding;

     Command 2 - Speed/Sensitivity:

       Fan Speed
     !Velocity Tracker not initialized.
     #Pixel distance must be non-negative
     #pipelineAccelerationInferenceEvents
     $BoxWithConstraintsScopeImpl(density=
     (Selects the playback speed of the media.
     (config_viewMaxRotaryEncoderFlingVelocity
     (config_viewMinRotaryEncoderFlingVelocity                                                                         , airMouseSpeed=
     , density=
     , displayLargeEmphasized=
     , displayMediumEmphasized=
     , displaySmallEmphasized=
     , initialVelocity:
     , lastDensity=
     , mousePointerSpeed=
     , scrollSpeed=
     , velocity=
     , velocityCoefficient=
     ,initial velocity:                                                                                                :maximumVelocity should be a positive value. You specified=
     ACCELERATION_ALLOWLIST_FETCH
     ACCELERATION_ALLOWLIST_GET
     ACCELERATION_ANALYTICS
     AnchoredDraggableState was configured through a constructor without providing positional and velocity
     threshold...
     CREATE TABLE IF NOT EXISTS `DeviceConfig` (`macAddress` TEXT NOT NULL, `mousePointerSpeed` INTEGER NOT
     NULL, `airMouseSpeed` INTEGER NOT NULL, `scrollSpeed` INTEGER NOT NULL, `layoutScreen` INTEGER NOT NULL...
     CREATE TABLE IF NOT EXISTS `DeviceConfig` (`macAddress` TEXT NOT NULL, `mousePointerSpeed` INTEGER NOT
     NULL, `airMouseSpeed` INTEGER NOT NULL, `scrollSpeed` INTEGER NOT NULL, `mouseJiggleMode` TEXT NOT NULL
     DEFAULT 'disabled'...
     Controls the speed of the fan.
     DensityImpl(density=
     DensityWithConverter(density=
     DisplayLargeEmphasized
     DisplayMediumEmphasized
     DisplaySmallEmphasized
     DrawParams(density=
     FanSpeed
     FlingInfo(initialVelocity=
     INSERT INTO `DeviceConfig` (`macAddress`,`mousePointerSpeed`,`airMouseSpeed`,`scrollSpeed`,
`mouseJiggleMode`,`layoutScreen`,`layoutScreenLayoutId`,`keyboardLanguageLayout`,`lastUsedDate`,
     `addedDate`) VALUES (?,?,?,?,?,?,?,?,?,?)
     InlineDensity(density=
     L physicalPixelDisplaySizeRatio=1.0 rotation=0 offsetX=0 offsetY=0 scale=1.0}
     Landroid/view/VelocityTracker;
     LocalDensity
     PIPELINE_ACCELERATION_ANALYTICS
     Pixel
     Pixel 6
     Pixel Tablet
     Speed
     The density on DrawerState (
     UPDATE `DeviceConfig` SET `macAddress` = ?,`mousePointerSpeed` = ?,`airMouseSpeed` = ?,`scrollSpeed` = ?,
     `mouseJiggleMode` = ?,`layoutScreen` = ?,`layoutScreenLayoutId` = ?,`keyboardLanguageLayout` = ?,
     `lastUsedDate` = ?,`addedDate` = ? WHERE `macAddress` = ?                                                         accelerationAllowlistLogEvent
     airMouseSpeed
     air_mouse_speed
     air_mouse_speed_preview
     complexToDimensionPixelSize
     computeCurrentVelocity
     density
     densityDpi
     endVelocityVector
     getAxisVelocity
     getCurrVelocity
     getDensity                                                                                                        getDimensionPixelOffset
     getDimensionPixelSize
     getMaxFlingVelocity
     getMetrics
     getMinFlingVelocity
     getOnDensityChanged$ui
     getScaledMaximumFlingVelocity
     getScaledMinimumFlingVelocity
     getWaveSpeed
     getXVelocity
     getYVelocity
     heightPixels
     mousePointerSpeed
     mouse_pointer_speed
     mouse_scroll_speed
     screenHeightDp
     screenWidthDp
     scrollSpeed
     setDensity
     setFrameContentVelocity
     setOnDensityChanged$ui
setFrameContentVelocity
     setOnDensityChanged$ui
     setWaveSpeed
     smallestScreenWidthDp
     velocityThreshold
     velocityVector
     widthPixels

     Command 3 - Mouse Handler:
                                                                                                                       MouseInputHandler

     Command 4 - Mouse Buttons:

     #visible_mouse_buttons_multi_preview
     , showMouseButtonsBottom=
     , showMouseButtonsTop=
     , visibleMouseButtons=
     MouseButton(mouseButton=
     MouseButtonClick(mouseButton=
     show_mouse_buttons
     show_mouse_buttons_preview
     visible_mouse_buttons

     Command 5 - Scroll Settings:

     , invertScrolling=
     , mLastScrollDelta=
     , reverseScrolling=false)                                                                                         , scrollDelta=
     MouseWheelScrollDelta(value=

     Command 6 - Touch Modes:

     , startFullScreen=
     9Landroid/view/ViewTreeObserver$OnTouchModeChangeListener;
     ACFullScreenView
     FULLSCREEN
     Fullscreen
     Outlined.Fullscreen
     ToggleFullscreen                                                                                                  addOnTouchModeChangeListener
     fullscreen
     fullscreen_button_msg
     fullscreen_drag_msg
     isInTouchMode
     onTouchModeChanged
     removeOnTouchModeChangeListener
     reportFullscreenMode
     setFocusableInTouchMode
     setFullScreenIntent
     start_full_screen

     Command 7 - Preferences:

     (no output)
Summary

     The reference APK uses a sophisticated touchpad implementation with:

     1. Room database for per-device configuration
     2. Three speed settings (pointer, air mouse, scroll)
     3. Invertible scroll with delta control
     4. Fullscreen drag mode for window positioning
     5. Pointer trail visualization via PointerPathView
     6. Configurable mouse buttons with top/bottom layout options


next agent 

Root Cause Analysis

     There are 5 distinct bugs contributing to the broken connection status display:

     Bug A — MouseTrackpadActivity never updates its visual connection indicators

     The connectionDot (red circle) and connectionLabel ("Disconnected" text) are created in onCreate() at
     lines 142–162 and never referenced again. The updateConnectionState() method at line 263 is dead code:

     private fun updateConnectionState(connected: Boolean, label: String) {
         val dot = findViewById<View>(0x7f0a0001) // arbitrary ID, won't match any view
         // Use tag-based approach instead                                                                                 statusText.text = label  // only updates statusText, not the dot or label
     }

     This method:

     - Looks up a view by a hardcoded resource ID 0x7f0a0001 that matches nothing (the views are created
       programmatically, not from XML)
     - Only writes to statusText, not to connectionDot or connectionLabel
     - Is never actually called from anywhere

     Result: The dot stays red and the label stays "Disconnected" forever, regardless of actual connection
     state.

     Bug B — Timing race between transport creation and touch events

     connectTransport() (line 412) launches a coroutine on scope.launch (line 425). The transport isn't
     assigned to udpTransport until the coroutine completes. Any touch event arriving before that finds
     udpTransport == null and triggers:
                                                                                                                       // Line 274-278
     if (transport == null || !transport.isConnected) {
         showError("Not connected — reconnecting...")
         connectTransport()
     }

     This produces a spurious "Not connected" error even when connection is in progress.

     Bug C — UdpTransport.connect() is a local-only operation, not a reachability check

     UdpTransport.connect() at transport-wifi/.../UdpTransport.kt:110-155:
                                                                                                                       - Creates a DatagramSocket
     - Sets isConnected = true (line 144)
     - Starts send/receive loops
     - Returns true

     It does not send a test packet or wait for any peer response. connect() succeeds even if the receiver IP
     is wrong, the receiver is off, or there's a firewall blocking UDP. The socket opening proves only local
     availability.

     BridgeService correctly handles this (BUG-090 fix at BridgeService.kt:339-342):

     DiagnosticsManager.update {
         copy(transportMode = "UDP", transportConnected = false, targetIp = targetIp)
     }

     It keeps transportConnected = false until a PONG or accepted PAIR_RESPONSE arrives. But
     MouseTrackpadActivity trusts connect() directly and sets udpTransport = transport on success (line 430).
Bug D — MouseTrackpadActivity creates an independent transport

     MouseTrackpadActivity creates its own UdpTransport (line 427-428) completely separate from BridgeService's
     transport. The DiagnosticsData.transportConnected flag observed by BridgeScreen is only set by
     BridgeService. The trackpad's transport state is invisible to the main UI.

     Bug E — showError() auto-hides but no success path hides it
                                                                                                                       showError() at line 257-260 shows the error text for 5 seconds then hides it. But there's no corresponding
     showSuccess() or updateStatus() call after connectTransport() succeeds that would clear any prior error
     message.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Data Flow Summary

     User opens MOUSE → MouseTrackpadActivity.onCreate()
       → connectTransport() [coroutine launched]
         → UdpTransport(config, isSender=true)
         → transport.connect() [opens local socket, returns true immediately]
         → udpTransport = transport [assigned on IO thread]
         → statusText updated [but NOT connectionDot or connectionLabel]

     User touches screen → handleTouch()
       → checks udpTransport?.isConnected
       → if udpTransport is still null (race) → "Not connected — reconnecting..."
       → if udpTransport is set → works, but dot/label still red/"Disconnected"                                   
     BridgeService (separate process):
       → creates its OWN UdpTransport
       → starts ping loop
       → waits for PONG → DiagnosticsManager.update { transportConnected = true }
       → BridgeScreen observes DiagnosticsManager.state → green dot, "Connected to X"

     The trackpad has two visual problems:

     1. Red dot + "Disconnected" label never update (Bug A)
     2. "Not connected" error flashes during connection race (Bug B)
                                                                                                                       ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Proposed Fix

     Fix for MouseTrackpadActivity

     1. Store connectionDot and connectionLabel as class-level fields (like statusText and errorText already
        are)
     2. Update them in connectTransport() after transport.connect() returns
     3. Add a connecting flag to suppress the race-condition error
     4. Optionally: collect UdpTransport.connectionState flow for reactive state updates (handles disconnects
        gracefully)

     Fix for UdpTransport (optional but recommended)

     The isConnected flag in UdpTransport should have two levels:

     - isSocketOpen — local socket is created (what it currently does)
     - isPeerReachable — at least one PONG/PAIR_RESPONSE received

     MouseTrackpadActivity should check isPeerReachable (or subscribe to connectionState), not just isConnected.
MouseTrackpadActivity should check isPeerReachable (or subscribe to connectionState), not just isConnected.

     Fix for the "Not connected" error during connection

     Add a @Volatile var isConnecting = false flag. Set it to true before launching the coroutine, set to false
     after assignment. In handleTouch(), check isConnecting and show "Connecting..." instead of "Not connected
     — reconnecting...".
                                                                                                                       ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Files Involved

     ┌─────────────────────────────────────────┬─────────┬─────────────────────────────────────────────────────┐
     │File                                     │Line(s)  │Issue                                                │
     ├─────────────────────────────────────────┼─────────┼─────────────────────────────────────────────────────┤
     │app-bridge/.../MouseTrackpadActivity.kt  │142-162  │connectionDot/connectionLabel never updated          │
     ├─────────────────────────────────────────┼─────────┼─────────────────────────────────────────────────────┤
     │app-bridge/.../MouseTrackpadActivity.kt  │263-267  │updateConnectionState() is broken dead code          │
     ├─────────────────────────────────────────┼─────────┼─────────────────────────────────────────────────────┤
     │app-bridge/.../MouseTrackpadActivity.kt  │274-278  │Race: null check shows spurious error                │
     ├─────────────────────────────────────────┼─────────┼─────────────────────────────────────────────────────┤
     │app-bridge/.../MouseTrackpadActivity.kt  │412-444  │connectTransport() doesn't update visual indicators  │
     ├─────────────────────────────────────────┼─────────┼─────────────────────────────────────────────────────┤
     │transport-wifi/.../UdpTransport.kt       │110-155  │connect() returns true without reachability proof    │
     ├─────────────────────────────────────────┼─────────┼─────────────────────────────────────────────────────┤
     │app-bridge/.../BridgeService.kt          │339-342  │Already fixed (BUG-090) — waits for PONG             │
     └─────────────────────────────────────────┴─────────



next agent 

Research Findings

     How Android touch delegation works in this layout

     ViewGroup.dispatchTouchEvent() routes ACTION_DOWN to the topmost child whose bounds contain the touch
     point. Since the ConstraintLayout has non-overlapping children (trackpad, left button, right button,
     scroll zone), touch events are automatically routed to the correct view — no custom interception logic
     needed.

     Reference implementations studied                                                                            
     ┌────────────────────────┬─────────────────────────────────┬──────────────────────────────────────────────┐
     │Project                 │Pattern                          │Key insight                                   │
     ├────────────────────────┼─────────────────────────────────┼──────────────────────────────────────────────┤
     │GhostBoard (GitHub)     │Separate trackpad View + left/   │Left/right buttons at bottom, trackpad above; │
     │                        │right button Views               │setOnTouchListener on each                    │
     ├────────────────────────┼─────────────────────────────────┼──────────────────────────────────────────────┤
     │Linkpad (GitHub)        │Compose with edge scroll strip + │Right-edge scroll strip is a standard pattern │
     │                        │mouse buttons                    │                                              │
     ├────────────────────────┼─────────────────────────────────┼──────────────────────────────────────────────┤
     │FreeRDP TouchPointerView│FrameLayout with clustered       │Puck drag + separate scroll button with       │
     │                        │buttons                          │morphing height                               │
     ├────────────────────────┼─────────────────────────────────┼──────────────────────────────────────────────┤
     │AppGround "Bluetooth    │ConstraintLayout with            │Left/right zones split into left_up/bar/down  │
     │Keyboard & Mouse"       │PointerPathView + button zones   │sub-zones                                     │
     ├────────────────────────┼─────────────────────────────────┼──────────────────────────────────────────────┤
     │Android TouchDelegate   │Parent extends child hit area    │Useful for small buttons but not needed here  │
     │docs                    │                                 │                                              │
     └────────────────────────┴─────────────────────────────────┴──────────────────────────────────────────────┘  
     Why this architecture works

     1. No overlap between button zones and trackpad → Android routes touches correctly
     2. Buttons consume their own touches (return true from onTouch) → trackpad never sees them
     3. Trackpad handles gestures only in its own bounds → getLocationOnScreen() converts local coords to
        screen coords for CursorGoto
     4. Scroll zone is a dedicated strip → no conflict with trackpad's two-finger scroll

     What Changed

     ┌────────────────┬───────────────────────────────────────┬────────────────────────────────────────────────┐       │Aspect          │Before                                 │After                                           │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Root layout     │FrameLayout                            │ConstraintLayout                                │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Touch zones     │1 (full screen)                        │4 (trackpad, left btn, right btn, scroll strip) │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Left click      │Long-press gesture only                │Dedicated "L" button + gesture fallback         │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Right click     │Long-press gesture only                │Dedicated "R" button + gesture fallback         │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Scroll          │Two-finger only on trackpad            │Dedicated right-edge strip + two-finger fallback│
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Cursor          │Used raw event.getX/Y (local to full   │Uses getLocationOnScreen() (correct for bounded │
     │positioning     │screen)                                │view)                                           │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Button labels   │None                                   │"L" and "R" text on buttons                     │
     ├────────────────┼───────────────────────────────────────┼────────────────────────────────────────────────┤
     │Button feedback │None                                   │Background darkens on press, restores on release│
     └────────────────┴───────────────────────────────────────┴────────────────────────────────────────────────┘

next agent 
Current Bottleneck

     CursorOverlayService.kt:139 calls windowManager.updateViewLayout(view, params) on every cursor position
     update. This is problematic because:

     1. updateViewLayout() is a synchronous Binder IPC call to WindowManagerService. At 60fps input that's 60
        Binder round-trips/second.
     2. Each call triggers a full relayout() cycle: measure → layout → draw, even though the view is a fixed-             size cursor that never changes dimensions.
     3. The StateFlow collection on Dispatchers.Main means each emission synchronously invokes the Binder call
        before the next emission can be processed.

     Research Findings — Ranked by Speed

     Tier 1: SurfaceControl.Transaction (best, complex)

     - Zero Binder overhead per frame — batched and committed async at VSYNC boundary.
     - Used by Android's own SurfaceAnimationRunner for all window animations.
     - Requires accessing ViewRootImpl.getSurfaceControl() (hidden API) or View.getRootSurfaceControl().
     - API 29+ (matches our minSdk), but AttachedSurfaceControl.applyTransactionOnDraw is API 33+.
     - Verdict: Maximum performance but requires reflection or API-gating. Not worth the complexity for a small
       cursor overlay.

     Tier 2: translationX/Y + invalidate() (best practical option)

     - No Binder call — just marks the view dirty for redraw.
     - invalidate() → next VSYNC → onDraw() → RenderThread composites. One draw, zero IPC.                             - ~10x cheaper than requestLayout() which triggers measure+layout+draw.
     - View size stays fixed in LayoutParams; only the rendering transform changes.
     - Verdict: The clear winner for this use case. The cursor is a tiny 32dp view with a simple Path draw —
       onDraw is sub-millisecond.

     Tier 3: SurfaceView with lockHardwareCanvas() (overkill)

     - Separate rendering thread, bypasses UI thread entirely.
     - Best for continuous full-screen rendering (games, video).
     - Adds surface lifecycle complexity (SurfaceHolder callbacks).
     - For a 32dp cursor, the overhead of managing a separate Surface exceeds the benefit.
     - Verdict: Not appropriate here. Designed for heavy rendering loads.                                         
     Tier 4: LowLatencyCanvasView (AndroidX, API 14+)

     - Front-buffered rendering for sub-frame latency (stylus/drawing apps).
     - Still experimental. Adds AndroidX dependency.
     - Verdict: Designed for drawing apps, not cursor overlays. Unnecessary.

     Recommended Approach

     Replace updateViewLayout() with translationX/Y + invalidate(), with optional Choreographer batching:

     StateFlow emission → store target (x,y) → Choreographer.postFrameCallback →
       on doFrame: view.translationX = target.x; view.translationY = target.y; view.invalidate()

     Why Choreographer batching matters: Multiple cursor positions within one 16ms frame coalesce to a single
     invalidate(). Without it, each StateFlow emission triggers a separate draw pass.

     Key implementation detail: The view's LayoutParams.x/y should be set once at creation (to 0,0) and never
     changed. All movement goes through translationX/Y. The onDraw path doesn't need to recompute the path —
     it's identical every frame, just rendered at a different offset.
Additional optimizations:

     - setLayerType(LAYER_TYPE_HARDWARE, null) on the cursor view — caches the rendered cursor as a GPU texture;
        only the transform matrix updates each frame (no re-draw of the Path).
     - Consider view.invalidate() only when position actually changed (skip if delta < 1px).

     This approach eliminates the Binder bottleneck while keeping the code simple and compatible with API 29.

next agent . 

Research Report: Android USB HID Blacklist & Mouse Capture Workarounds

     1. The Root Cause — AOSP UsbHostManager Blacklist

     The blacklist is hardcoded in UsbHostManager.java (present in every Android version from 4.x to 15+):

     // Current AOSP (renamed to isDenyListed in Android 15+)
     private boolean isDenyListed(int clazz, int subClass) {
         if (clazz == UsbConstants.USB_CLASS_HUB) return true;
         return clazz == UsbConstants.USB_CLASS_HID
             && subClass == UsbConstants.USB_INTERFACE_SUBCLASS_BOOT;
     }                                                                                                            
     What this does:

     - Any USB device with interfaceClass=3 (HID) AND interfaceSubclass=1 (BOOT) is never added to mDevices
     - It never appears in UsbManager.getDeviceList()
     - ACTION_USB_DEVICE_ATTACHED broadcast is never sent
     - UsbManager.openDevice() throws SecurityException ("device is on a restricted bus")
     - This is by design — AOSP assumes the kernel's HID driver will handle these devices as system input
       devices

     The critical insight: The kernel's usbhid driver still processes the device. The Linux kernel creates /dev/
     input/event* nodes. Android's InputReader reads from those nodes. The device works as a mouse — it's just
     invisible to the USB Host API.

     2. The Portronics Key2 Combo Dongle

     The nano USB receiver is a 2.4GHz wireless receiver that presents two HID boot interfaces (keyboard +
     mouse), both with class=3, subclass=1. This means both interfaces are blacklisted by AOSP.
                                                                                                                       The kernel still handles it via usbhid, creating at least two /dev/input/event* nodes (one for keyboard,
     one for mouse).

     3. Current State of the Bridge

     From BridgeService.kt:

     - checkPreAttachedUsb() and startUsbPolling() scan UsbManager.deviceList — will never find the device
       because it's blacklisted
     - The usbReceiver BroadcastReceiver for ACTION_USB_DEVICE_ATTACHED — will never fire for this device
     - Keyboard capture works via AccessibilityService.onKeyEvent() — confirmed working
     - Mouse capture: not implemented at all — no onMotionEvent() or onGenericMotionEvent() handler exists        
     4. All Possible Workarounds for USB Mouse Capture (Android 10, No Root)

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     APPROACH A: AccessibilityService.onMotionEvent() — ❌ NOT AVAILABLE

     API level required: 34 (Android 14)

     // This API was added in Android 14 (API 34)
     override fun onMotionEvent(event: MotionEvent) { ... }

     The Redmi 9 runs Android 10 (API 29). This API does not exist on this device. Cannot use.

     However, the companion API AccessibilityServiceInfo.setMotionEventSources() is also API 34+. Even if we
     somehow got a custom ROM, we'd need API 34.
APPROACH B: Transparent Overlay Window with onGenericMotionEvent — ✅ MOST PROMISING

     API level: 14+ (works on Android 10)

     The Android input system routes mouse MotionEvents to the focused window. We can create a transparent
     overlay window that receives mouse events.

     How it works:

     1. Create a TYPE_APPLICATION_OVERLAY window (API 26+) or TYPE_SYSTEM_ALERT (API < 26)                             2. Set FLAG_NOT_TOUCH_MODAL + FLAG_NOT_FOCUSABLE so it doesn't intercept touch
     3. Implement View.OnGenericMotionListener on the overlay view
     4. Mouse hover/movement events are delivered to the overlay

     Key considerations:

     - FLAG_NOT_TOUCHABLE would prevent receiving mouse events — must NOT use this flag
     - FLAG_NOT_FOCUSABLE prevents key events but still allows MotionEvent delivery
     - The overlay must have a non-zero size (even 1x1 pixel) to receive events
     - ACTION_HOVER_MOVE, ACTION_HOVER_ENTER, ACTION_HOVER_EXIT are the relevant mouse events
     - Need SYSTEM_ALERT_WINDOW permission (granted via Settings)

     Limitations:

     - Only one overlay window can receive events at a time
     - Other apps' overlays may interfere
     - The overlay must be visible (even if fully transparent)

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────  
     APPROACH C: Read /dev/input/event* via Shizuku (ADB process) — ✅ VIABLE

     API level: any (uses app_process)

     Shizuku runs a process with shell UID (2000) via app_process. The shell user has read access to /dev/input/
     event* nodes.

     How it works:

     1. User enables Wireless Debugging (one-time setup)
     2. Start Shizuku service via adb shell sh /sdcard/Android/data/moe.shizuku.server/start.sh                        3. Our app connects to Shizuku's Binder
     4. Launch a UserService (runs as shell UID) that opens /dev/input/eventN and reads input_event structs
     5. Forward raw events to the bridge pipeline

     File permissions check:

     shell@generic_x86_64:/ $ ls -la /dev/input/
     crw-rw---- 1 system input 13, 64 ... event0
     crw-rw---- 1 system input 13, 65 ... event1

     The input group — shell is typically in the input group on stock Android, so it can read these.

     Limitations:

     - Requires user to enable Wireless Debugging and start Shizuku (one-time)
     - Need to identify which eventN corresponds to the mouse (use getevent -p)
     - Must parse raw input_event structs (JNI or Kotlin/Native)

     ─────────────────────────────────────────────────────────────────────
APPROACH D: Read /dev/input/event* via ADB shell — ✅ VIABLE (simpler than Shizuku)

     API level: any

     A simpler variant of Approach C. Instead of Shizuku, run a native binary via Runtime.getRuntime().exec(
     "cat /dev/input/eventN") over an ADB connection.

     This is what the ClevCode HID reader and android-digitizer projects do.

     Steps:

     1. User connects phone to a computer via USB                                                                      2. Computer runs adb forward or adb exec-out to read the event stream
     3. Pipe the raw input_event data to our bridge app

     Limitations:

     - Requires a computer in the loop (not standalone)
     - Not practical for our use case (bridge should work standalone)

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     APPROACH E: Use /dev/uhid to Create a Virtual Mouse Device — ✅ CREATIVE

     API level: any (kernel feature, requires shell/root access)

     UHID (User-space HID) allows creating virtual HID devices from userspace. The kernel's HID subsystem
     treats them as real devices.

     How it would work:
                                                                                                                       1. Open /dev/uhid (requires shell/root — chmod 666 /dev/uhid or ADB shell)
     2. Send UHID_CREATE2 with a mouse HID descriptor
     3. Send UHID_INPUT2 events with parsed mouse data
     4. Android's InputReader picks up the virtual device as a real mouse

     The catch: This doesn't help us read the mouse — it helps us create a virtual mouse. The blacklisted USB
     device still can't be read via USB Host API. We'd need to read the physical device first (via /dev/input/
     event* or raw USB) and then re-inject via UHID.

     Use case: If we could read the physical mouse events (Approach C/D), we could use UHID on the receiver
     phone to create a virtual mouse there. But this is overly complex.
                                                                                                                       Android has a built-in hid command (in frameworks/base/cmds/hid/) that uses UHID. It's accessible via:

     export CLASSPATH=/system/framework/input.jar
     app_process /system/bin com.android.commands.hid.Hid

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     APPROACH F: AOA 2.0 HID Accessory Mode — ✅ CREATIVE BUT REQUIRES NEW HARDWARE

     API level: 12+

     Android Open Accessory 2.0 allows a USB accessory to present itself as a HID device. But this requires:

     1. A custom USB accessory/microcontroller between the dongle and the phone
     2. The accessory forwards HID reports to Android via AOA control requests
     3. Android sees the accessory as a regular USB device (not blacklisted)

     Not applicable for our Portronics dongle — we'd need custom hardware.


     APPROACH G: Xposed/Magisk to Patch isBlackListed — ⚠️  REQUIRES ROOT

     One Stack Overflow user mentioned: "I will try to use the Xposed to replace the method isBlackListed by
     always return false."

     This would:

     1. Hook UsbHostManager.isDenyListed() via Xposed Framework
     2. Always return false
     3. The HID device would appear in UsbManager.deviceList()
     4. Our existing UsbInputCapture would work directly

     Limitation: Requires rooted device + Xposed/Magisk. Our target is unrooted Redmi 9.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────  
     APPROACH H: Modify Framework System Property — ❌ NOT POSSIBLE

     The blacklist is hardcoded Java logic, not a system property. There is no persist.sys.usb.hid_blacklist or
     similar property to disable it.

     The config_usbHostBlacklist (now config_usbHostDenylist) is a resource array that only controls bus
     address blacklisting (e.g., /dev/bus/usb/001/), not the class/subclass check.

     The persist.sys.usb.config property controls USB gadget mode (MTP, ADB, etc.) — completely unrelated.

     Conclusion: No system property can disable the HID boot device blacklist.                                    
     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     APPROACH I: AccessibilityService Flag for Mouse Events on Pre-34 — ✅ PARTIAL

     While onMotionEvent() requires API 34, there's a workaround for API 24-33:

     The OpenMouse project demonstrates this approach:

     1. Create an AccessibilityService
     2. Set FLAG_SEND_MOTION_EVENTS in the service info XML
     3. Use TouchInteractionController.Callback.onMotionEvent() — this is available on API 24+

     Wait — actually FLAG_SEND_MOTION_EVENTS is API 34 too. Let me re-check...

     Actually, looking at the OpenMouse source code more carefully, for API 24-33 they use a CursorView (
     regular View) approach, not AccessibilityService for mouse capture. The AccessibilityService.onMotionEvent(
     ) is only used on API 34+.

     For API 24-33, the CursorView approach is:

     5. Recommended Solution for Our Project

     Primary approach: AccessibilityService.onMotionEvent() (API 34+) + Transparent Overlay (API 24-33)

     Since our bridge phone is Android 10 (API 29), the immediate viable approaches are:                          
     1. For Android 10-13 (API 29-33): Use a transparent overlay window (Approach B) to capture mouse
        MotionEvents. The overlay must be added by the bridge service and kept alive.

     2. For Android 14+ (API 34+): Use AccessibilityService.onMotionEvent() with setMotionEventSources(
        SOURCE_MOUSE) — this is cleaner and doesn't require an overlay.

     3. Fallback / power-user option: Support Shizuku-based raw event reading (Approach C) for users who want
        direct /dev/input access.

     Key implementation details for Approach B (our immediate need):

     // In BridgeService or a new MouseCaptureService
     val overlayView = View(context)
     overlayView.setOnGenericMotionListener { _, event ->
         if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
             when (event.action) {
                 MotionEvent.ACTION_HOVER_MOVE -> {
                     // dx = event.x - lastX, dy = event.y - lastY                                                                     // emit MouseMove event
                 }
                 MotionEvent.ACTION_BUTTON_PRESS -> { /* MouseButtonDown */ }
                 MotionEvent.ACTION_BUTTON_RELEASE -> { /* MouseButtonUp */ }
                 MotionEvent.ACTION_SCROLL -> {
                     val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                     // emit Scroll event
                 }
             }
             true
         } else false
     }                                                                                                            
     val params = WindowManager.LayoutParams(
         1, 1, // 1x1 pixel
         WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
         WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
             WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
         PixelFormat.TRANSLUCENT
     )
     windowManager.addView(overlayView, params)

     Critical flags:

     - FLAG_NOT_FOCUSABLE — prevents intercepting keyboard events (those go to AccessibilityService.onKeyEvent)
     - Do NOT use FLAG_NOT_TOUCHABLE — that would block mouse events too
     - TYPE_APPLICATION_OVERLAY — requires SYSTEM_ALERT_WINDOW permission




     6. Summary Table

     ┌───────────────────────────────────────┬───────────┬────────────┬───────────┬───────────┬────────────────┐
     │Approach                               │API Level  │Root Needed │ADB Needed │Standalone │Effort          │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │A: AccessibilityService.onMotionEvent  │34+        │No          │No         │Yes        │Low             │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │B: Transparent overlay                 │14+        │No          │No         │Yes        │Medium          │       ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │C: Shizuku /dev/input                  │Any        │No          │One-time   │Yes        │High            │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │D: ADB shell cat                       │Any        │No          │Always     │No         │Low             │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │E: /dev/uhid virtual device            │Any        │Yes         │No         │N/A        │N/A             │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │F: AOA 2.0 accessory                   │12+        │No          │No         │Yes        │Hardware        │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │G: Xposed/Magisk patch                 │Any        │Yes         │No         │Yes        │Medium          │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │H: System property                     │N/A        │N/A         │N/A        │N/A        │Impossible      │
     ├───────────────────────────────────────┼───────────┼────────────┼───────────┼───────────┼────────────────┤
     │I: InputDevice API                     │Any        │No          │No         │Yes        │N/A (read-only) │
     └───────────────────────────────────────┴───────────┴────────────┴───────────┴───────────┴────────────────┘

     Bottom line: For our Android 10 Redmi 9, Approach B (transparent overlay) is the only practical standalone
     solution that doesn't require root or a computer in the loop. The overlay captures ACTION_HOVER_MOVE
     events from the USB mouse, which we then bridge to the receiver.

     ▣  General · MiMo V2.5 Free · 6m 38s

 next agent 
Implementation Plan: End-to-End Latency Measurement & Socket Optimization

     Research Summary

     Key findings:

     ┌────────────────────┬────────────────────────────────────────────────────────────────────────────────────┐
     │Topic               │Finding                                                                             │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │Best timing API     │SystemClock.elapsedRealtimeNanos() — monotonic, includes deep sleep, nanosecond     │       │                    │resolution via CLOCK_MONOTONIC                                                      │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │Kernel event        │getevent -lt uses CLOCK_MONOTONIC with microsecond precision — matches our System.  │
     │timestamps          │nanoTime() approach                                                                 │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │WiFi low-latency    │WifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY, tag) disables power save on  │
     │                    │Android 10+                                                                         │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │Socket buffers for  │For real-time: small buffers (64KB) to minimize queuing delay. Large buffers cause  │
     │latency             │bufferbloat                                                                         │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │Traffic class       │DatagramSocket.setTrafficClass(0x10) = IPTOS_LOWDELAY. Falls back silently on OEM   │
     │                    │kernels                                                                             │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │SO_PRIORITY         │Not directly available on Android DatagramSocket; use setTrafficClass() which sets  │
     │                    │IP TOS byte                                                                         │
     ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │SurfaceFlinger      │dumpsys SurfaceFlinger --latency <window> for display pipeline; not relevant for    │
     │                    │our app-to-app measurement                                                          │       ├────────────────────┼────────────────────────────────────────────────────────────────────────────────────┤
     │WALT hardware       │Google's open-source latency timer ($50 hardware) — gold standard but not needed    │
     │                    │here                                                                                │
     └────────────────────┴────────────────────────────────────────────────────────────────────────────────────┘

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Architecture

     Bridge Device (Redmi 9)                    Receiver Device (OnePlus Pad Go)
     ┌─────────────────────────┐                ┌─────────────────────────┐
     │ USB HID input event     │                │                         │                                            │  t0 = event.timestampNs │                │                         │
     │        │                │                │                         │
     │  t1 = SystemClock       │                │                         │
     │      .elapsedRealtimeNanos()             │                         │
     │        │                │                │                         │
     │  EventPacketFactory     │   UDP Packet   │  Packet arrives         │
     │  (embeds t1 in payload) │ ─────────────> │  t2 = elapsedRealtimeNanos() │
     │        │                │                │        │                │
     │  UdpTransport.send()    │                │  AccessibilityCommandBus│
     │  t3 = elapsedRealtimeNanos()             │  handleEvent()          │
     │        │                │                │  t4 = elapsedRealtimeNanos() │
     │                         │                │        │                │
     │  captureToSendUs = t3-t1│                │  receiveToInjectUs = t4-t2  │
     │                         │                │                         │
     │  DiagnosticsData:       │                │  DiagnosticsData:       │
     │  - captureToSendUs      │                │  - receiveToInjectUs    │
     │  - latencyMs (PING/PONG)│                │  - e2eEstimateMs        │
     │  - networkLatencyEstMs  │                │  - latencyMs            │
     └─────────────────────────┘                └─────────────────────────┘

     End-to-end estimate = captureToSendUs + networkLatencyEstMs + receiveToInjectUs
Files to Modify

     1. DiagnosticsData.kt — Add latency fields

     // Add after line 78 (receiveToInjectUs):

     /** Estimated one-way network transit (half of last PING/PONG RTT). */
     val networkLatencyEstUs: Long = 0L,
     /** End-to-end estimate: captureToSend + networkEst + receiveToInject (microseconds). */
     val e2eLatencyEstUs: Long = 0L,
     /** P50 capture-to-send over last 100 samples. */
     val captureToSendP50Us: Long = 0L,
     /** P99 capture-to-send over last 100 samples. */
     val captureToSendP99Us: Long = 0L,
     /** P50 receive-to-inject over last 100 samples. */
     val receiveToInjectP50Us: Long = 0L,
     /** P99 receive-to-inject over last 100 samples. */
     val receiveToInjectP99Us: Long = 0L,
                                                                                                                       2. DiagnosticsManager.kt — Add latency percentile tracking

     Add rolling percentile tracking for the new fields:

     // After latencySamples (line 65):

     private const val LATENCY_PERCENTILE_WINDOW = 100
     private val captureToSendSamplesLock = Any()
     private val captureToSendSamples = ArrayDeque<Long>()
     private val receiveToInjectSamplesLock = Any()
     private val receiveToInjectSamples = ArrayDeque<Long>()
                                                                                                                       fun recordCaptureToSendUs(us: Long) {
         val (p50, p99) = synchronized(captureToSendSamplesLock) {
             captureToSendSamples.addLast(us)
             if (captureToSendSamples.size > LATENCY_PERCENTILE_WINDOW) captureToSendSamples.removeFirst()
             percentiles(captureToSendSamples)
         }
         update { copy(captureToSendUs = us, captureToSendP50Us = p50, captureToSendP99Us = p99) }
     }

     fun recordReceiveToInjectUs(us: Long) {
         val (p50, p99) = synchronized(receiveToInjectSamplesLock) {
             receiveToInjectSamples.addLast(us)
             if (receiveToInjectSamples.size > LATENCY_PERCENTILE_WINDOW) receiveToInjectSamples.removeFirst()
             percentiles(receiveToInjectSamples)
         }
         update { copy(receiveToInjectUs = us, receiveToInjectP50Us = p50, receiveToInjectP99Us = p99) }
     }

     fun updateNetworkEstimate(rttMs: Long) {
         val oneWayUs = (rttMs * 1000) / 2
         update { copy(networkLatencyEstUs = oneWayUs) }
     update { copy(networkLatencyEstUs = oneWayUs) }
     }

     fun updateE2eEstimate(captureUs: Long, networkUs: Long, injectUs: Long) {
         update { copy(e2eLatencyEstUs = captureUs + networkUs + injectUs) }
     }

     private fun percentiles(samples: ArrayDeque<Long>): Pair<Long, Long> {
         if (samples.isEmpty()) return Pair(0L, 0L)                                                                        val sorted = samples.toList().sorted()
         val p50 = sorted[(sorted.size * 0.5).toInt().coerceAtMost(sorted.size - 1)]
         val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]
         return Pair(p50, p99)
     }

     3. BridgeService.kt — Measure capture-to-send with elapsedRealtimeNanos

     In startCapture(), change the timing from System.nanoTime() to SystemClock.elapsedRealtimeNanos() (line
     ~805):

     // Replace:
     //   val t0 = System.nanoTime()
     // With:
     val t0 = android.os.SystemClock.elapsedRealtimeNanos()

     // Replace:
     //   lastCaptureToSendUs.set((System.nanoTime() - t0) / 1_000L)
     // With:                                                                                                          val us = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000L
     DiagnosticsManager.recordCaptureToSendUs(us)

     Also update the PONG handler to feed network estimate into E2E:

     // In startIncomingLoop, PONG branch (line ~475):
     val latency = System.currentTimeMillis() - sentAt
     if (latency in 0L..10_000L) {
         lastPongReceivedMs = System.currentTimeMillis()
         DiagnosticsManager.recordLatency(latency)
         DiagnosticsManager.updateNetworkEstimate(latency)
         // Recompute E2E estimate                                                                                         val captureUs = DiagnosticsManager.state.value.captureToSendUs
         val networkUs = DiagnosticsManager.state.value.networkLatencyEstUs
         val injectUs = DiagnosticsManager.state.value.receiveToInjectUs
         DiagnosticsManager.updateE2eEstimate(captureUs, networkUs, injectUs)
         DiagnosticsManager.update { copy(transportConnected = true) }
     }

dont use code as it is write yours 

     4. AccessibilityCommandBus.kt — Measure receive-to-inject with elapsedRealtimeNanos

     In the init block (line ~194):

     // Replace:
     //   val t0 = System.nanoTime()
     // With:
     val t0 = android.os.SystemClock.elapsedRealtimeNanos()

     // Replace:
     //   lastInjectUs.set((System.nanoTime() - t0) / 1_000L)
     // With:
     val us = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000L                                            DiagnosticsManager.recordReceiveToInjectUs(us)

     5. ReceiverService.kt — Flush E2E estimate on counter tick

     In the counter flush loop (line ~285), add:

     // After the existing injectUs flush:
     val e2eUs = DiagnosticsManager.state.value.e2eLatencyEstUs
     if (e2eUs > 0L) {
         // Already computed in BridgeService PONG handler; just ensure it's visible
     }

     6. UdpTransport.kt — Socket optimization

     Replace the current socket tuning (lines 127-129) with optimized values:

     // Current:
     //   runCatching { sock.sendBufferSize    = SOCKET_BUFFER_BYTES }  // 256KB
     //   runCatching { sock.receiveBufferSize = SOCKET_BUFFER_BYTES }  // 256KB                                       //   runCatching { sock.trafficClass      = TRAFFIC_CLASS_LOWDELAY }

     // Optimized for low latency:
     private const val SOCKET_BUFFER_BYTES = 64 * 1024  // 64KB — small to minimize queuing

     runCatching { sock.sendBufferSize    = SOCKET_BUFFER_BYTES }  // 64KB
     runCatching { sock.receiveBufferSize = SOCKET_BUFFER_BYTES }  // 64KB
     runCatching { sock.trafficClass      = 0x10 }  // IPTOS_LOWDELAY (RFC 1349)

     7. BridgeService.kt — Add WiFi low-latency lock

     Add a WiFi lock to disable power save during active bridging:                                                
     // New fields:
     private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

     // In onCreate():
     val wifiManager = getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
     wifiLock = wifiManager?.createWifiLock(
         android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
         "InputBridge::LowLatency"
     )

     // In startPipeline(), after transport connects:
     wifiLock?.acquire()

     // In onDestroy(), before serviceScope.cancel():
     runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
     wifiLock = null

     8. MouseTrackpadActivity.kt — Display real-time latency in the trackpad UI

     Add a latency indicator to the status bar area. In onCreate(), add after the statusText (line ~223):

     // Latency display
     val latencyText = TextView(this).apply {
         text = "Latency: --"
         setTextColor(0x99FFFFFF.toInt())
         textSize = 10f
         setPadding(0, dp(4), 0, 0)
     }                                                                                                                 bottomPanel.addView(latencyText)

     // In scope.launch, after transport connects:
     scope.launch {
         DiagnosticsManager.state.collect { data ->
             val e2eUs = data.e2eLatencyEstUs
             val captureUs = data.captureToSendUs
             val injectUs = data.receiveToInjectUs
             val rttMs = data.latencyMs
             withContext(Dispatchers.Main) {
                 if (e2eUs > 0) {
                     latencyText.text = "E2E: %.1fms | Send: %.1fms | Net: %.1fms | Inj: %.1fms".format(
                         e2eUs / 1000.0,
                         captureUs / 1000.0,
                         data.networkLatencyEstUs / 1000.0,
                         injectUs / 1000.0,
                     )
                 } else if (rttMs > 0) {
                     latencyText.text = "RTT: ${rttMs}ms"                                                                          }
             }
         }
     }

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Latency Budget Breakdown

     Based on research, typical Android input latency:

     ┌─────────────────────────────────────┬─────────────┬────────────────┬────────────────────────────────────┐       │Stage                                │Typical      │Our Target      │Measurement                         │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │USB HID poll → event parsed          │1-4ms        │<2ms            │UsbInputCapture event timestamp     │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │Event → packet created               │<0.1ms       │<0.1ms          │EventPacketFactory.fromEvent()      │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │Packet → socket.send() returns       │0.1-1ms      │<0.5ms          │captureToSendUs                     │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │Network transit (WiFi LAN)           │1-5ms        │<3ms            │PING PONG RTT / 2                   │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │Receiver socket → packet parsed      │<0.5ms       │<0.5ms          │Included in receive loop            │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │Packet → accessibility inject        │0.5-5ms      │<2ms            │receiveToInjectUs                   │
     ├─────────────────────────────────────┼─────────────┼────────────────┼────────────────────────────────────┤
     │Total E2E estimate                   │3-15ms       │<10ms           │Sum of above                        │
     └─────────────────────────────────────┴─────────────┴────────────────┴────────────────────────────────────┘

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Socket Optimization Settings

Socket Optimization Settings

     ┌──────────────────────┬────────────────────────────┬─────────────────────────────────────────────────────┐
     │Setting               │Value                       │Rationale                                            │       ├──────────────────────┼────────────────────────────┼─────────────────────────────────────────────────────┤
     │SO_SNDBUF             │64KB                        │Small = low queuing latency (gaming/real-time rule)  │
     ├──────────────────────┼────────────────────────────┼─────────────────────────────────────────────────────┤
     │SO_RCVBUF             │64KB                        │Matches send; prevents bufferbloat                   │
     ├──────────────────────┼────────────────────────────┼─────────────────────────────────────────────────────┤
     │trafficClass          │0x10 (IPTOS_LOWDELAY)       │Hints to router/AP for expedited forwarding          │
     ├──────────────────────┼────────────────────────────┼─────────────────────────────────────────────────────┤
     │WiFi lock             │WIFI_MODE_FULL_LOW_LATENCY  │Disables power save, keeps radio active              │
     ├──────────────────────┼────────────────────────────┼─────────────────────────────────────────────────────┤
     │TRANSFER_TIMEOUT_MS   │50ms                        │Already set — keeps USB poll loop responsive         │
     └──────────────────────┴────────────────────────────┴─────────────────────────────────────────────────────┘
                                                                                                                       ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Key Implementation Notes

     1. Use SystemClock.elapsedRealtimeNanos() everywhere — it's monotonic, includes deep sleep, and has
        nanosecond resolution via CLOCK_MONOTONIC. System.nanoTime() works but is ~2x slower on older Android (
        < API 30) due to missing @CriticalNative annotation.

     2. No protocol changes needed — the existing PING/PONG RTT gives us network latency. We don't need to
        embed timestamps in data packets (which would require coordinated protocol version bumps).

     3. Rolling percentiles (P50/P99) — more useful than averages for latency. A 99th percentile under 10ms
        means 99% of input events feel instant.

     4. WiFi low-latency lock — requires CHANGE_WIFI_STATE permission and foreground service. Disables 802.11
        power save, which can add 50-200ms of latency when the radio wakes from doze.

     5. 64KB socket buffers — counterintuitive but correct for real-time. Large buffers (256KB+) absorb bursts
        but add queuing delay. For 125Hz mouse input, 64KB holds ~500 packets — far more than needed.

    
next agent 
Key Findings

     1. There is no "2-second burst limit" — the real limit is one-active-gesture

     - GestureDescription.getMaxGestureDuration() = 60 seconds (not 2)
     - GestureDescription.getMaxStrokeCount() = 20 strokes per gesture
     - Only one gesture can be active at a time. A new dispatchGesture() cancels any in-progress gesture.
     - The "2-second cooldown" is actually the queue backlog: each dispatched gesture generates MotionEvents at
       16ms intervals (display refresh rate). A rapid sequence of short gestures fills the Handler message
       queue faster than it drains.

     2. willContinue + continueStroke is the mechanism for continuous movement

     The StrokeDescription(path, startTime, duration, willContinue) constructor and continueStroke() method
     allow a gesture to keep the finger "down" across multiple dispatchGesture() calls:

     // First call: finger goes down, willContinue = true keeps it down                                                val stroke1 = StrokeDescription(path, 0, duration, true)
     dispatchGesture(Builder().addStroke(stroke1).build(), null, null)

     // Second call: continueStroke picks up exactly where stroke1 ended
     val stroke2 = stroke1.continueStroke(newPath, 0, newDuration, false)
     dispatchGesture(Builder().addStroke(stroke2).build(), null, null)

     Critical constraint: The continuation path MUST start at the exact endpoint of the previous stroke. The
     MotionEventInjector.prepareToContinueOldGesture() verifies this — if coordinates don't match, the gesture
     is cancelled.

     3. AccessibilityNodeInfo.performAction(ACTION_CLICK) bypasses gestures entirely                              
     For clicks, there's a much faster path: find the node at the cursor position and call performAction(
     ACTION_CLICK). This:

     - Bypasses dispatchGesture() entirely
     - Has no gesture queue overhead
     - Calls View.performClick() directly on the target
     - Works on any clickable view

     Limitation: requires canRetrieveWindowContent="true" (which we already have) and the target view must
     expose ACTION_CLICK in its accessibility node.

     4. performGlobalAction() is also gesture-free

     Navigation actions (BACK, HOME, RECENTS) already use performGlobalAction() — no gesture overhead. These
     are instant.

5. The reference APK likely uses one of these strategies

     Based on analysis of the AOSP source and known third-party mouse-over-Android projects:

     - Strategy A: Bluetooth HID transport (our "Path A") — injects at kernel level, no accessibility needed
     - Strategy B: Proper willContinue chaining with a gesture state machine
     - Strategy C: performAction(ACTION_CLICK) for clicks + dispatchGesture only for swipes/scrolls
     - Strategy D: Hybrid — track virtual cursor, use performAction when a clickable node is found, fall back
       to gesture otherwise                                                                                       
     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Optimal Strategy for Lowest Latency

     Recommended approach: Hybrid injection with gesture continuation

     ┌───────────┬───────────────────────────────────────────────────────────┬─────────────────────────────────┐
     │Action     │Method                                                     │Why                              │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤
     │Mouse move │Virtual cursor update only (no injection)                  │Already optimized — cursorX/Y    │
     │           │                                                           │updated inline                   │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤
     │Left click │performAction(ACTION_CLICK) on node at cursor, fallback to │Bypasses gesture system when     │
     │           │dispatchGesture(tap)                                       │possible                         │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤
     │Right click│dispatchGesture(longPress)                                 │No accessibility equivalent for  │
     │           │                                                           │long-press                       │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤       │Scroll     │Single dispatchGesture(swipe) with short duration          │One gesture per scroll event is  │
     │           │                                                           │fine                             │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤
     │Keyboard   │injectKeyCode() via performAction                          │Already gesture-free             │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤
     │Navigation │performGlobalAction()                                      │Already gesture-free             │
     ├───────────┼───────────────────────────────────────────────────────────┼─────────────────────────────────┤
     │Continuous │willContinue=true + continueStroke state machine           │Only way to simulate finger-hold-│
     │drag       │                                                           │and-drag                         │
     └───────────┴───────────────────────────────────────────────────────────┴─────────────────────────────────┘

     Implementation outline for click optimization                                                                
     // In AccessibilityCommandBus or InputBridgeAccessibilityService
     fun clickAtPosition(x: Float, y: Float) {
         val node = findClickableNodeAt(x, y)
         if (node != null && node.isClickable) {
             // Fast path: direct node click, no gesture
             node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
         } else {
             // Fallback: gesture tap
             tap(x, y)
         }
     }

     private fun findClickableNodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
         val root = rootInActiveWindow ?: return null
         // Hit-test: find node whose bounds contain (x, y)
         return findNodeInTree(root, x, y)
     }

     Implementation outline for gesture continuation (drag)
 // State machine for continuous gesture
     private var currentStroke: StrokeDescription? = null
     private var gestureActive = false

     fun beginDrag(x: Float, y: Float) {                                                                                   val path = Path().apply { moveTo(x, y) }
         currentStroke = StrokeDescription(path, 0, 16, true) // willContinue=true
         dispatchGesture(Builder().addStroke(currentStroke!!).build(), null, null)
         gestureActive = true
     }

     fun continueDrag(x: Float, y: Float) {
         if (!gestureActive || currentStroke == null) return
         val path = Path().apply {
             moveTo(currentStroke!!.path.let {
                 val pm = PathMeasure(it, false)
                 floatArrayOf(0f, 0f).also { pts -> pm.getPosTan(pm.length, pts, null) }
             }.let { moveTo(it[0], it[1]);.lineTo(x, y) })
         }
         val continued = currentStroke!!.continueStroke(path, 0, 16, true)
         dispatchGesture(Builder().addStroke(continued).build(), null, null)
         currentStroke = continued
     }
                                                                                                                       fun endDrag(x: Float, y: Float) {
         if (!gestureActive || currentStroke == null) return
         val path = Path().apply {
             moveTo(/* last position */)
             lineTo(x, y)
         }
         val final = currentStroke!!.continueStroke(path, 0, 16, false) // willContinue=false
         dispatchGesture(Builder().addStroke(final).build(), null, null)
         gestureActive = false
         currentStroke = null
     }
                                                                                                                       ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Summary of Answers

     Summary of Answers

     ┌──────────────────────────────┬──────────────────────────────────────────────────────────────────────────┐
     │Question                      │Answer                                                                    │
     ├──────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
     │Can we queue multiple         │No — each dispatchGesture cancels the previous. Use willContinue chaining │
     │gestures back-to-back?        │instead.                                                                  │
     ├──────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
     │Is there a way to bypass the  │There is no 2-second limit. The 60s max duration is per-gesture. The      │
     │2-second limit?               │issue is queue backlog from rapid cancel→restart cycles.                  │       ├──────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
     │Can we use                    │Yes — for clicks, use performAction(ACTION_CLICK) on the node. For hover/ │
     │AccessibilityService to move  │cursor position, there's no direct API — the virtual cursor overlay is    │
     │cursor without                │the correct approach.                                                     │
     │dispatchGesture?              │                                                                          │
     ├──────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
     │Can we use performAction for  │Yes — and it's faster. node.performAction(ACTION_CLICK) calls View.       │
     │click instead of gesture?     │performClick() directly.                                                  │
     ├──────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
     │How does the reference APK    │Likely via Bluetooth HID transport (our Phase 6), which injects at kernel │
     │inject mouse HID reports?     │level without accessibility. The accessibility path is the fallback for   │
     │                              │apps that don't support BT HID.                                           │
     └──────────────────────────────┴──────────────────────────────────────────────────────────────────────────┘

     Recommended Next Step

     Implement the hybrid click approach: find the clickable node at cursor position, use performAction(
     ACTION_CLICK) when available, fall back to dispatchGesture(tap) otherwise. This eliminates gesture
     overhead for ~80% of click targets (standard Android widgets).

next agent 

     Here is the complete WiFi low-latency UDP optimization research:

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     WiFi UDP Low-Latency Optimization — Research Findings

     Current State (UdpTransport.kt)

     Your transport already has two optimizations:

     - Socket buffers: SO_SNDBUF / SO_RCVBUF = 256 KB (line 30, applied at lines 127-128)
     - DSCP marking: trafficClass = 0x28 (EF — Expedited Forwarding, line 33, applied at line 129)                
     Both are wrapped in runCatching (best-effort, silently ignored on OEM kernels that don't support them).

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     1. WifiLock with WIFI_MODE_FULL_LOW_LATENCY — HIGH IMPACT, MUST ADD

     This is the single most impactful change. On Android 10+ (API 29, your minSdk), WIFI_MODE_FULL_LOW_LATENCY
     tells the WLAN driver to disable power save (802.11 doze) and keep the radio in active TX/RX state.
     Without it, the WiFi radio enters doze between beacons, adding 10-100ms spikes every few seconds.

     // In BridgeService or ReceiverService — acquire on start, release on stop
     val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
     val wifiLock: WifiManager.WifiLock = wifiManager.createWifiLock(
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
             WifiManager.WIFI_MODE_FULL_LOW_LATENCY
         else
             WifiManager.WIFI_MODE_FULL_HIGH_PERF,  // deprecated fallback for API <29
         "InputBridgeLowLatency"                                                                                       )
     wifiLock.acquire()
     // ... later on stop:
     wifiLock.release()

     Requirements for low-latency mode (from AOSP docs):

     - WiFi enabled + connected
     - App in foreground
     - Screen on
     - Device manufacturer must support NL80211_CMD_SET_POWER_SAVE in the WLAN driver
                                                                                                                       Known limitation: Low-latency mode does NOT work with WiFi Direct (P2P) connections — only with
     infrastructure WiFi (normal AP). This was confirmed by Google as "intended behavior."

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     2. WiFi Direct (P2P) vs Regular WiFi — DO NOT USE FOR THIS USE CASE

     Research findings:

     - WiFi Direct P2P has periodic 100ms+ jitter spikes every 2-3 seconds and throughput drops every ~60
       seconds — this is MAC-layer power save behavior that cannot be disabled
     - Google confirmed this is "intended behavior" for P2P connections
     - WIFI_MODE_FULL_LOW_LATENCY does NOT work with WiFi Direct
     - WiFi Direct connection establishment takes 5-15 seconds (unacceptable for your bridge)
     - Through WiFi infrastructure (AP), both devices on the same BSSID get proper QoS treatment

     Verdict: Stick with regular WiFi LAN (both devices on same AP). WiFi Direct would be worse for latency.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     3. Socket Buffer Sizes — ALREADY OPTIMIZED

     Your 256 KB buffers are well-chosen. Research findings:

     - Linux default: rmem_default = 212,992 bytes (~208 KB). Android typically inherits this.
     - rmem_max on Android: typically 212,992 bytes. SO_RCVBUF cannot exceed this without root.
     - 256 KB may be capped to 212,992 by the kernel — verify with getReceiveBufferSize() after setting.
     - For real-time input (tiny packets, high rate), smaller buffers are actually better — they reduce queuing
       delay. 256 KB absorbs bursts of mouse-move packets but won't cause multi-second queuing delay.
     - The kernel already applies rmem_max as a hard cap. Setting 640 KB on Android is silently truncated to
       ~160-212 KB.
                                                                                                                       Recommendation: Keep 256 KB. It's the sweet spot for burst absorption without bufferbloat.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     4. DSCP / IP_TOS — ALREADY OPTIMIZED

     Your trafficClass = 0x28 (DSCP EF = Expedited Forwarding, decimal 46) is correct.

     - IPTOS_LOWDELAY = 0x10 (DSCP CS1) — basic low-delay hint
     - 0x28 = DSCP EF (Expedited Forwarding) — stronger priority marking
     - Android's DatagramSocket.setTrafficClass() maps to IP_TOS socket option
     - Best-effort: many Android OEM kernels ignore this or remap it. But it costs nothing.
     - The AP and router may honor DSCP to prioritize these packets in the WiFi queue (WMM EDCA: Voice > Video
       > Best Effort)

     Recommendation: Already optimal. No change needed.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────
                                                                                                                       5. MulticastLock — REQUIRED FOR DISCOVERY

     Your AutoDiscovery.kt uses UDP broadcast (255.255.255.255). Without a MulticastLock, the Android WiFi
     stack filters out broadcast packets via APF (Android Packet Filter) firmware — this is why Pixel 2/XL had
     known issues receiving UDP broadcasts.

     val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
     val multicastLock = wifiManager.createMulticastLock("InputBridgeDiscovery")
     multicastLock.setReferenceCounted(false)
     multicastLock.acquire()
     // Release after discovery is complete
     multicastLock.release()                                                                                      
     Note: This is only needed during the discovery phase (port 54322), not during normal data transport.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     6. 5GHz vs 2.4GHz — PREFER 5GHz

     - 5GHz delivers 3-10ms lower ping than 2.4GHz under good conditions
     - 5GHz has less jitter in congested environments (apartments, offices)
     - 5GHz has 24 non-overlapping channels vs 3 on 2.4GHz
     - Trade-off: 5GHz has shorter range and worse wall penetration
     - For your use case (two devices on the same AP, likely same room), 5GHz is clearly superior
     - Use 80MHz channel width on 5GHz for best balance of throughput and reliability

     Recommendation: Both devices should connect to the 5GHz band of the same AP.


     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     7. ConnectivityManager requestNetwork with NET_CAPABILITY_PRIORITIZE_LATENCY — NICE-TO-HAVE

     Android 14+ (API 34) supports network slicing with NET_CAPABILITY_PRIORITIZE_LATENCY. However:

     - Requires carrier/ISP support for network slicing                                                                - Only works on 5G networks (not WiFi)
     - Your target devices: Redmi 9 (API 29) and OnePlus Pad Go (API 33) — neither supports API 34 slicing

     Verdict: Not applicable to this project.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Summary: Priority-Ordered Optimization Checklist

     ┌───────┬────────────────────────────────────────────────┬──────────────────────────────────┬─────────────┐
     │Priorit│Optimization                                    │Impact                            │Already Done?│
     │y      │                                                │                                  │             │       ├───────┼────────────────────────────────────────────────┼──────────────────────────────────┼─────────────┤
     │1      │WifiLock(WIFI_MODE_FULL_LOW_LATENCY) — disable  │Critical — eliminates 10-100ms    │NO — ADD THIS│
     │       │WiFi power save                                 │spikes                            │             │
     ├───────┼────────────────────────────────────────────────┼──────────────────────────────────┼─────────────┤
     │2      │MulticastLock during discovery                  │High — broadcast packets filtered │NO — ADD THIS│
     │       │                                                │without it                        │             │
     ├───────┼────────────────────────────────────────────────┼──────────────────────────────────┼─────────────┤
     │3      │SO_SNDBUF / SO_RCVBUF = 256 KB                  │Medium — burst absorption         │✅ Yes       │
     ├───────┼────────────────────────────────────────────────┼──────────────────────────────────┼─────────────┤
     │4      │IP_TOS = 0x28 (DSCP EF)                         │Low-Medium — AP may prioritize    │✅ Yes       │
     ├───────┼────────────────────────────────────────────────┼──────────────────────────────────┼─────────────┤
     │5      │Both devices on 5GHz band                       │Medium — lower baseline latency   │User config  │
     ├───────┼────────────────────────────────────────────────┼──────────────────────────────────┼─────────────┤
     │6      │WiFi Direct P2P                                 │Negative — worse latency, broken  │Correctly    │
     │       │                                                │power save                        │avoided      │
     └───────┴────────────────────────────────────────────────┴──────────────────────────────────┴─────────────┘

     The missing piece is the WifiLock with WIFI_MODE_FULL_LOW_LATENCY. This single change should eliminate the
     periodic latency spikes that WiFi power save introduces.



     ───────────────────────────────────────────────────────────────────────────────────────────────────────────
next agent 


     Recommendation: Keep Trackpad as Pure Views, Bridge Screen as Compose

     Analysis of Current Code

     MouseTrackpadActivity.kt — Pure View system with setOnTouchListener:

     - Processes raw MotionEvent directly in handleTouch() (line 272)
     - Touch handler computes deltas, thresholds, sends UDP packets
     - Minimal abstraction: View → MotionEvent → your code
     - Uses Handler for long-press detection, simple and zero-overhead                                            
     BridgeScreen.kt — Compose:

     - Simple status UI, no high-frequency touch handling
     - Uses collectAsStateWithLifecycle for state observation
     - Compose is appropriate here (declarative, less boilerplate)

     Key Research Findings

     ┌────────────┬────────────────────────────────┬───────────────────────────────────────────────────────────┐
     │Factor      │View (setOnTouchListener)       │Compose (pointerInteropFilter / pointerInput)              │
     ├────────────┼────────────────────────────────┼───────────────────────────────────────────────────────────┤
     │Touch       │MotionEvent → your code (1 hop) │MotionEvent → PointerEvent conversion → hit testing →      │
     │pipeline    │                                │pointer input filter → your code (3-4 hops)                │
     ├────────────┼────────────────────────────────┼───────────────────────────────────────────────────────────┤
     │Event       │Zero overhead                   │PointerInteropFilter converts MotionEvent to Compose's     │
     │conversion  │                                │PointerEvent internally                                    │
     ├────────────┼────────────────────────────────┼───────────────────────────────────────────────────────────┤
     │Per-frame   │~0.5ms (AndroidOwner:onTouch    │Additional composition + layout + draw phases, even if     │       │cost        │per DataDog benchmarks)         │skipped                                                    │
     ├────────────┼────────────────────────────────┼───────────────────────────────────────────────────────────┤
     │State       │No recomposition needed         │Any mutableStateOf read in composition triggers            │
     │triggers    │                                │recomposition                                              │
     ├────────────┼────────────────────────────────┼───────────────────────────────────────────────────────────┤
     │Coroutine   │None for sync touch handling    │pointerInput uses coroutine suspension; LaunchedEffect     │
     │overhead    │                                │allocates scope per key change                             │
     ├────────────┼────────────────────────────────┼───────────────────────────────────────────────────────────┤
     │120fps      │8.3ms per frame budget          │Composition phase must complete within budget              │
     │deadline    │                                │                                                           │
     └────────────┴────────────────────────────────┴───────────────────────────────────────────────────────────┘
                                                                                                                       Why Views Win for Trackpad

     1. Zero conversion overhead: MotionEvent goes directly to handleTouch(). Compose would add PointerEvent
        conversion + 3-pass event propagation (Initial → Main → Final).

     2. No recomposition on touch: The trackpad's handleTouch() mutates plain fields (lastX, lastY, isDragging).
         In Compose, these would need mutableStateOf, triggering recomposition on every drag move — at 120fps
        touch reporting, that's ~120 recompositions/second just for tracking state.

     3. pointerInteropFilter is a fallback, not primary: The docs explicitly say "Prefer pointerInput and use
        this only for interoperation with existing code that consumes MotionEvents." Using it as the primary
        touch mechanism is fighting Compose's architecture.

     4. pointerInput uses coroutines: Each awaitPointerEvent() suspends the coroutine and re-allocates on
        resume. For a trackpad that processes 100+ MOVE events/second, this adds measurable allocation pressure.

     5. No phase-skipping benefit for hot touch state: Compose can skip composition if state is read in Layout
        or Draw phase (via lambda modifiers like Modifier.offset { }). But touch handling must happen in
        composition or pointer input — there's no way to defer it.
 5. No phase-skipping benefit for hot touch state: Compose can skip composition if state is read in Layout
        or Draw phase (via lambda modifiers like Modifier.offset { }). But touch handling must happen in
        composition or pointer input — there's no way to defer it.

     Specific Compose Overheads That Would Hurt

     ┌────────────────────────────────────────────────┬───────────────────┬────────────────────────────────────┐
     │Overhead                                        │Source             │Impact                              │
     ├────────────────────────────────────────────────┼───────────────────┼────────────────────────────────────┤
     │MotionEvent → PointerEvent conversion           │PointerInteropFilte│~0.1-0.3ms per event                │
     │                                                │r                  │                                    │       ├────────────────────────────────────────────────┼───────────────────┼────────────────────────────────────┤
     │3-pass event propagation                        │Initial/Main/Final │3x traversal per touch event        │
     ├────────────────────────────────────────────────┼───────────────────┼────────────────────────────────────┤
     │remember { mutableStateOf(...) } slot table     │State tracking     │Allocation + slot table O(n) gap    │
     │writes                                          │                   │moves                               │
     ├────────────────────────────────────────────────┼───────────────────┼────────────────────────────────────┤
     │LaunchedEffect coroutine scope per key change   │Effect handlers    │~200μs launch overhead per restart  │
     ├────────────────────────────────────────────────┼───────────────────┼────────────────────────────────────┤
     │Composition phase execution                     │Compose runtime    │Must complete before layout/draw    │
     └────────────────────────────────────────────────┴───────────────────┴────────────────────────────────────┘

     Recommendation                                                                                               
     Keep MouseTrackpadActivity as pure Views. The current implementation is correct and optimal for low-
     latency touch processing. The View system's setOnTouchListener provides the shortest path from hardware
     touch event to your UDP packet send.

     Keep BridgeScreen as Compose. It's a simple declarative UI with no high-frequency touch handling — exactly
     where Compose shines.

     Do not unify them. The attempt to make everything Compose would add measurable latency to the trackpad
     without any benefit. The two activities serve different purposes and have different performance
     requirements.

     If You Ever Need to Add Features to the Trackpad

     If you need richer UI in the trackpad (animations, complex layouts), consider:

     - AndroidView wrapper: host the existing View-based trackpad inside a Compose shell for the outer UI
     - Keep the touch-sensitive View as a pure View child, not a Composable
     - Use Modifier.drawWithCache only for static visual elements (not touch handling)
next agent 


     Current State

     MouseTrackpadActivity.kt (line 331-333): Pure linear scaling — dx * sensitivity with a user slider from 0.
     1x to 5.0x. No acceleration, no smoothing.

     AccessibilityCommandBus.kt (line 170-173): Receives raw dx/dy, clamps to screen bounds, updates cursor
     position. No processing — all scaling is done on the bridge side.

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Research Findings
                                                                                                                       1. Acceleration Curves from Real Systems

     Windows XP Pointer Ballistics: 5-point piecewise-linear lookup table indexed on vector magnitude sqrt(
     dx²+dy²). Below a threshold, input is multiplied by 1x. Above, it doubles or quadruples. Uses subpixel
     accumulation.

     macOS (IOHIPointing): 4th-degree polynomial: f(v) = linear·v + parabolic·v² + cubic·v³ + quartic·v⁴ where
     v is input speed. Has a capSpeedLinear where the curve transitions to pure linear extrapolation.

     Android TouchpadInputMapper (AOSP source): Piecewise-linear segments indexed by physical speed in mm/s:

     segments = {
       {10.922 mm/s, slope=3.19, intercept=0},
       {31.750 mm/s, slope=4.79, intercept=-17.526},
       {98.044 mm/s, slope=7.28, intercept=-96.52},
       {∞,           slope=15.04, intercept=-857.758},
     }

     Output speed = input_speed * gain(input_speed) where gain = slope + intercept/input_speed.                   
     Source Engine (Valve): sensitivity = pow(raw_delta, exponent) * scale + base_sensitivity. Exponent
     defaults to 1.0 (linear), tunable.

     RawAccel (competitive gaming): Linear acceleration: gain = 1 + a * (V - offset) where V = sqrt(dx²+dy²) /
     interval. Output cap enforced.

     2. The 1€ Filter (Best Smoothing Solution)

     From Casiez et al. (CHI 2012) — used in Google Chrome on Android, Unreal Engine, and libinput. The key
     insight:
                                                                                                                       - At low speed: Low cutoff frequency → smooth out jitter, tolerate some lag
     - At high speed: High cutoff frequency → minimize lag, tolerate jitter

     Two parameters: minCutoff (jitter control) and beta (lag control). Algorithm:

     speed = |x_curr - x_prev| / dt
     cutoff = minCutoff + beta * speed
     α = 1 / (1 + 1/(2π·cutoff·dt))
     x_filtered = α * x_curr + (1-α) * x_prev

     3. Scroll Sensitivity

     AOSP SCROLL_PIXEL_MULTIPLIER = 120f with 80ms swipe duration. libinput uses two-finger scrolling with a
     built-in distance threshold. Natural scrolling = content moves in same direction as fingers (opposite of
     traditional scrollbar behavior).

     ───────────────────────────────────────────────────────
 Mathematical Model
                                                                                                                       Optimal Mouse Movement Pipeline

     Touch Input (dx, dy in pixels)
         │
         ▼
     [1. DPI Scaling] ─── Normalize by screen density ratio
         │
         ▼
     [2. Acceleration Curve] ─── Nonlinear gain based on input speed
         │
         ▼
     [3. 1€ Filter] ─── Adaptive low-pass filter
         │
         ▼
     [4. Output to Receiver]

     1 DPI Scaling

     Phone touchscreen and tablet screen have different pixel densities:                                          
     phoneDpi = phoneMetrics.densityDpi  // e.g. 440 (Redmi 9)
     tabletDpi = 240                      // OnePlus Pad Go

     dpiRatio = phoneDpi / tabletDpi     // ≈ 1.83

     rawDx = touchEvent.x - lastX
     rawDy = touchEvent.y - lastY

     // A 1px movement on the phone should map to 1px on the tablet,
     // scaled by the physical size difference
     scaledDx = rawDx * dpiRatio                                                                                       scaledDy = rawDy * dpiRatio

     2 Acceleration Curve (Piecewise-Linear with Offset)

     Based on Android's AOSP touchpad curve, adapted for t
phoneDpi = phoneMetrics.densityDpi  // e.g. 440 (Redmi 9)
     tabletDpi = 240                      // OnePlus Pad Go

     dpiRatio = phoneDpi / tabletDpi     // ≈ 1.83

     rawDx = touchEvent.x - lastX
     rawDy = touchEvent.y - lastY                                                                                 
     // A 1px movement on the phone should map to 1px on the tablet,
     // scaled by the physical size difference
     scaledDx = rawDx * dpiRatio
     scaledDy = rawDy * dpiRatio

     2 Acceleration Curve (Piecewise-Linear with Offset)

     Based on Android's AOSP touchpad curve, adapted for touchscreen-to-cursor mapping:

     data class AccelSegment(
         val maxSpeed: Float,    // mm/s or px/frame — threshold for this segment
         val slope: Float,       // gain multiplier slope
         val intercept: Float    // gain offset
     )

     // Segments tuned for phone touchscreen → tablet cursor feel:
     // First segment: 1:1 (no acceleration) for small precision movements
     // Later segments: progressive acceleration for fast swipes                                                       val ACCEL_SEGMENTS = listOf(
         AccelSegment(maxSpeed = 8f,   slope = 1.0f,  intercept = 0f),
         AccelSegment(maxSpeed = 25f,  slope = 1.8f,  intercept = -6.4f),
         AccelSegment(maxSpeed = 80f,  slope = 2.5f,  intercept = -23.9f),
         AccelSegment(maxSpeed = Float.MAX_VALUE, slope = 3.0f, intercept = -63.9f),
     )

     fun computeAcceleration(dx: Float, dy: Float, sensitivity: Float): Pair<Float, Float> {
         val speed = sqrt(dx * dx + dy * dy)
         if (speed < 0.001f) return dx to dy

         // Find the segment                                                                                               val seg = ACCEL_SEGMENTS.first { speed <= it.maxSpeed }

         // gain = slope + intercept / speed (Android AOSP form)
         val gain = (seg.slope + seg.intercept / speed) * sensitivity
         val clampedGain = gain.coerceIn(0.5f, 4.0f)

         return (dx * clampedGain) to (dy * clampedGain)
     }

     Why this shape:

     - Below 8 px/frame: gain ≈ 1.0 (linear, precise for small adjustments)
     - 8–25 px/frame: gain rises to ~1.5 (slight boost for medium gestures)
     - 25–80 px/frame: gain rises to ~2.2 (fast swipes traverse screen quicker)
     - Above 80: capped at 3.0 (prevents uncontrollable acceleration)

     3 1€ Filter (Jitter Removal + Low Latency)
 class OneEuroFilter(
         private val minCutoff: Float = 1.0f,  // Hz — lower = less jitter, more lag
         private val beta: Float = 0.007f,      // — higher = less lag at high speed
         private val dCutoff: Float = 1.0f      // Hz — derivative filter cutoff
     ) {
         private var prevX = 0f
         private var prevFilteredX = 0f
         private var prevFilteredDX = 0f
         private var prevTimeNs = 0L

         // For 2D: track X and Y independently                                                                            private var prevY = 0f
         private var prevFilteredY = 0f
         private var prevFilteredDY = 0f

         fun filter(x: Float, y: Float, timestampNs: Long): Pair<Float, Float> {
             if (prevTimeNs == 0L) {
                 prevTimeNs = timestampNs
                 prevX = x; prevFilteredX = x
                 prevY = y; prevFilteredY = y
                 return x to y
             }

             val dt = ((timestampNs - prevTimeNs) / 1_000_000_000f).coerceAtLeast(0.001f)
             prevTimeNs = timestampNs

             // Estimate speed (derivative magnitude)
             val dx = x - prevX
             val dy = y - prevY
             val speed = sqrt(dx * dx + dy * dy) / dt                                                             
             // Adaptive cutoff: low at rest, high when moving fast
             val cutoff = minCutoff + beta * speed

             // Low-pass filter with adaptive cutoff
             val alpha = 1.0f / (1.0f + 1.0f / (2.0f * PI.toFloat() * cutoff * dt))

             val filteredX = alpha * x + (1f - alpha) * prevFilteredX
             val filteredY = alpha * y + (1f - alpha) * prevFilteredY

             prevX = x; prevFilteredX = filteredX
             prevY = y; prevFilteredY = filteredY                                                                 
             return filteredX to filteredY
         }
     }

     Tuning guide:

     - minCutoff = 1.0 — starts here when finger is still (removes touch jitter)
     - beta = 0.007 — increases cutoff linearly with speed (adds ~7 Hz per px/frame of speed)
     - At rest: cutoff ≈ 1 Hz → heavy smoothing, eliminates sub-pixel jitter
     - During fast swipe: cutoff ≈ 10+ Hz → nearly zero lag

     4 Scroll Sensitivity
fun computeScroll(deltaY: Float, phoneDpi: Int, tabletDpi: Int): Float {
         // Two-finger scroll delta in pixels → scroll amount
         // Normalize by DPI ratio so scrolling feels consistent
         val dpiRatio = phoneDpi.toFloat() / tabletDpi.toFloat()

         // Base multiplier (Android AOSP uses 120f for swipe simulation)
         // For trackpad-like feel, reduce to ~0.3–0.5 of that
         val scrollMultiplier = 40f * dpiRatio

         return deltaY * scrollMultiplier                                                                              }

     // Natural scrolling: negate deltaY so finger-down = content-down
     fun sendScroll(naturalScroll: Boolean, deltaY: Float) {
         val direction = if (naturalScroll) -1f else 1f
         sendScroll(computeScroll(deltaY, phoneDpi, tabletDpi) * direction)
     }

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Complete Integration Point

     The processing chain in MouseTrackpadActivity.handleTouch() should look like:

     private val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f)

     // Inside ACTION_MOVE, single finger:
     val rawDx = x - lastX
     val rawDy = y - lastY                                                                                             val sensitivity = prefs.bridgeSensitivity

     // [1] Acceleration
     val (accelDx, accelDy) = computeAcceleration(rawDx, rawDy, sensitivity)

     // [2] 1€ filter for jitter removal
     val (filteredDx, filteredDy) = filter.filter(accelDx, accelDy, event.eventTime * 1_000_000L)

     sendMouseMove(filteredDx, filteredDy)

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────
                                                                                                                       Summary of Key Decisions

     ┌──────────────────────┬──────────────────────────────────────────────────────────────────────────────────┐
     │Question              │Answer                                                                            │
     ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤
     │Linear or             │Piecewise-linear gain curve — linear at low speed for precision, progressive gain │
     │acceleration?         │at high speed for traversal                                                       │
     ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤
     │Phone DPI → tablet    │Multiply raw deltas by phoneDpi / tabletDpi ratio                                 │
     │DPI mapping?          │                                                                                  │
     ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤
     │Amplify small /       │No — amplify large, preserve small. Small = 1:1 gain. Large = up to 3x gain via   │
     │dampen large?         │acceleration curve                                                                │
     ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤
     │Jitter smoothing?     │1€ filter — adaptive low-pass. At rest: 1 Hz cutoff (smooth). Moving fast: 10+ Hz │
     │                      │cutoff (responsive). Adds <2ms latency                                            │
     ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────┤
     │Scroll multiplier?    │40 * (phoneDpi / tabletDpi) with optional natural scroll inversion                │
     └──────────────────────┴──────────────────────────────────────────────────────────────────────────────────┘


next last most imp agent 

Read ~/btmouse-jadx/sources/defpackage/y01.java
                                                                                                                       Thought: 165ms

     Now I have a comprehensive understanding of the reference APK. Let me compile all findings into a detailed
     analysis report.

     Here is the complete deep analysis of the "Bluetooth Keyboard Mouse" (BTMouse) v6.23.1 reference APK:

     ───────────────────────────────────────────────────────────────────────────────────────────────────────────

     Deep Analysis Results: io.appground.blek v6.23.1

     1. Smali Counts & Directory Structure

     - 0 smali files — the APK was only extracted (unzipped), not decompiled to smali
     - classes.dex (7MB) + classes2.dex (16KB) — raw DEX format
     - Native libs: only libandroidx.graphics.path.so and libdatastore_shared_counter.so — no native HID code

     2. App Identity
                                                                                                                       ┌─────────────────────────────────────┬───────────────────────────────────────────────────────────────────┐
     │Field                                │Value                                                              │
     ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Package                              │io.appground.blek                                                  │
     ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Version                              │6.23.1 (code 257)                                                  │
     ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Min SDK                              │23 (Android 6.0)                                                   │
     ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Target SDK                           │36                                                                 │
     ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Compile SDK                          │37                                                                 │       ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Application class                    │com.pairip.application.Application (obfuscated)                    │
     ├─────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
     │Language                             │Kotlin + Jetpack Compose UI                                        │
     └─────────────────────────────────────┴───────────────────────────────────────────────────────────────────┘

     3. Architecture (Deobfuscated with jadx)

     Service Hierarchy:

     kk2 (Service + LifecycleObserver)
       └── zx1 (abstract base — BT adapter, notification, input manager, sendReport)
             ├── ClassicHidService  (BluetoothHidDevice API — classic BT)
             └── BleHidService      (BLE GATT server — BLE HID)

     Key classes decoded:
Key classes decoded:

     ┌─────────────────┬───────────────────────────────────────────────────────────────────────────────────────┐
     │Obfuscated       │Role                                                                                   │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │zx1              │Abstract base service: BT lifecycle, notification, wakelock, p(byte, byte[]) sendReport│
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │ClassicHidService│Classic BT HID via BluetoothHidDevice.sendReport()                                     │       ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │BleHidService    │BLE HID via GATT server notifications                                                  │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │q00              │Input manager — holds HID report descriptor bytes and report senders                   │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │xx1              │Report sender callback: retries sendReport() up to 2 times                             │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │v00              │Keyboard HID report descriptor (Report ID 1)                                           │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │a10              │Mouse HID report descriptor (Report ID 2)                                              │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │r00              │Gamepad HID report descriptor (Report ID 6)                                            │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │x00              │Mouse report sender coroutine — builds 5-byte report: {buttons, x, y, wheel, pan}      │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │o74              │Settings data class (all UI settings)                                                  │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │w01              │DeviceConfig (per-device: mousePointerSpeed, airMouseSpeed, scrollSpeed,               │
     │                 │mouseJiggleMode)                                                                       │       ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │PointerPathView  │Custom cursor trail animation (extends ShapeableImageView)                             │
     ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
     │l42              │Input type enum: Keyboard or Gamepad                                                   │
     └─────────────────┴───────────────────────────────────────────────────────────────────────────────────────┘

     4. HID Report Descriptors (Decoded)

     Keyboard (Report ID 1):

     - Report size: 8 bytes
     - Byte 0: Report ID (0x01)                                                                                        - Byte 1: Modifier keys (8 bits: Ctrl/Shift/Alt/GUI)
     - Byte 2: Reserved (0x00)
     - Bytes 3-7: Key codes (5 simultaneous keys, Usage Page 0x07)

     Mouse (Report ID 2):

     - Report size: 6 bytes
     - Byte 0: Report ID (0x02)
     - Byte 1: Buttons (5 bits: L/R/M/Back/Forward) + 3 padding
     - Byte 2: X delta (signed, -127..127)
     - Byte 3: Y delta (signed, -127..127)
     - Byte 4: Wheel (signed, -127..127)
     - Byte 5: AC Pan / horizontal scroll (signed, -127..127)

     5. Report Sending Chain

     Touch/keyboard event
       → q00 (InputManager) holds report elements [v00 (kb), a10 (mouse), r00 (gamepad)]
       → x00 coroutine: a10.f.l.sendReport(byte reportId, byte[] data)
       → xx1.v(byte, byte[]): retries up to 2x
       → zx1.p(byte, byte[]): calls BluetoothHidDevice.sendReport() or BLE notify                                 
     Critical code in x00.x():

     this.o.f.l((byte) 2, new byte[]{buttons, x, y, wheel, pan});
     // reportId=2 (mouse), 5-byte payload

     6. Connection Mode

     - ClassicHidService: Uses BluetoothHidDevice API (system HID profile)
       - Registers via BluetoothHidDevice.registerApp() with SDP record
       - Sends via BluetoothHidDevice.sendReport(device, reportId, data)
     - BleHidService: Uses BLE GATT server
       - Advertises HID service UUID 00001812 (HID over GATT)
       - Sends via BluetoothGattServer.notifyCharacteristicChanged()
       - BLE report prepends reportId: bArr2[0] = b; System.arraycopy(data, 0, bArr2, 1, data.length)

     7. Settings/Device Config Schema

     DeviceConfig (                                                                                                      macAddress TEXT PRIMARY KEY,
       mousePointerSpeed INTEGER,
       airMouseSpeed INTEGER,
       scrollSpeed INTEGER,
       mouseJiggleMode TEXT DEFAULT 'disabled',
       layoutScreen INTEGER,
       layoutScreenLayoutId INTEGER,
       keyboardLanguageLayout TEXT,
       lastUsedDate INTEGER,
       addedDate INTEGER
     )
                                                                                                                       Settings include: useAirMouse, showMouseButtonsTop, showMouseButtonsBottom, visibleMouseButtons (Set),
     invertScrolling, enabledTouchClick, mousePointerSpeed, scrollSpeed, mouseJiggleMode.

     8. Key Differences vs. Our Project
 8. Key Differences vs. Our Project

     ┌─────────────────────┬──────────────────────────────────────────────────────┬────────────────────────────┐
     │Feature              │BTMouse (Reference)                                   │InputBridge (Ours)          │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │HID transport        │Classic BT HID + BLE GATT                             │UDP over WiFi               │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │Input type           │Keyboard + Mouse + Gamepad                            │Keyboard + Mouse            │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤       │Mouse buttons        │5 buttons (L/R/M/Back/Forward)                        │Currently only L/R          │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │Gamepad              │Full 4-axis + 16 buttons + hat                        │Not implemented             │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │Touchpad             │On-screen with PointerPathView                        │On-screen with cursor trail │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │Air mouse            │Accelerometer-based cursor                            │Not implemented             │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │Settings             │Per-device Room DB config                             │In-memory                   │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │UI                   │Jetpack Compose                                       │Jetpack Compose             │
     ├─────────────────────┼──────────────────────────────────────────────────────┼────────────────────────────┤
     │HID descriptor size  │68 bytes (kb) + 64 bytes (mouse) + 92 bytes (gamepad) │Varies                      │
     └─────────────────────┴──────────────────────────────────────────────────────┴────────────────────────────┘

     9. Native Libraries

     Only 2 tiny .so files — both are AndroidX libraries, no native HID implementation. All BT HID handling is
     done in pure Kotlin/Java via Android APIs.
