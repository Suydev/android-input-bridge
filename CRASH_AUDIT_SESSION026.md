# Crash Audit Findings — Session 026

## Agent 1: Bridge & Receiver Audit (12 findings)

### CRASH-1 (Critical) — BridgeAccessibilityService
- **File:** `app-bridge/.../service/BridgeAccessibilityService.kt:49`
- **Issue:** `CoroutineScope(Dispatchers.IO + SupervisorJob())` — no CoroutineExceptionHandler
- **Fix:** ✅ Added CoroutineExceptionHandler — DONE

### CRASH-2 (Critical) — MouseTrackpadActivity
- **File:** `app-bridge/.../ui/MouseTrackpadActivity.kt:53`
- **Issue:** `CoroutineScope(Dispatchers.IO + SupervisorJob())` — no CoroutineExceptionHandler
- **Fix:** ✅ Added CoroutineExceptionHandler — DONE

### CRASH-3 through CRASH-10 (High) — Unchecked `as` casts on getSystemService
- **Files:**
  - `BridgeService.kt:160` — `getSystemService(USB_SERVICE) as UsbManager`
  - `BridgeService.kt:936` — `getSystemService(NOTIFICATION_SERVICE) as NotificationManager`
  - `BridgeService.kt:946` — `getSystemService(NOTIFICATION_SERVICE) as NotificationManager`
  - `BridgeService.kt:952` — `getSystemService(POWER_SERVICE) as PowerManager`
  - `ReceiverService.kt:482` — `getSystemService(NOTIFICATION_SERVICE) as NotificationManager`
  - `ReceiverService.kt:489` — `getSystemService(NOTIFICATION_SERVICE) as NotificationManager`
  - `ReceiverService.kt:495` — `getSystemService(POWER_SERVICE) as PowerManager`
  - `CursorOverlayService.kt:68` — `getSystemService(WINDOW_SERVICE) as WindowManager`
  - `MouseTrackpadActivity.kt:232` — `getSystemService(WINDOW_SERVICE) as WindowManager`
  - `InputBridgeAccessibilityService.kt:112` — `getSystemService(WINDOW_SERVICE) as WindowManager`
- **Fix:** ✅ All changed to `as?` with null fallback — DONE

### CRASH-11 (Medium) — ReceiverViewModel
- **File:** `app-receiver/.../viewmodel/ReceiverViewModel.kt:152-153`
- **Issue:** `context.startService()` without runCatching — crashes in background
- **Fix:** ✅ Wrapped in runCatching — DONE

### CRASH-12 (Medium) — CursorOverlayService
- **File:** `app-receiver/.../service/CursorOverlayService.kt:57`
- **Issue:** `CoroutineScope(Dispatchers.Main + SupervisorJob())` — no CoroutineExceptionHandler
- **Fix:** ✅ Added CoroutineExceptionHandler — DONE

## Agent 2: Shared/Protocol/Transport Audit (7 findings)

### FINDING-1 (Critical) — AutoDiscovery socket.bind()
- **File:** `shared-core/.../discovery/AutoDiscovery.kt:57`
- **Issue:** `socket.bind(InetSocketAddress(DISCOVERY_PORT))` without try-catch
- **Fix:** ✅ Full rewrite with try-catch/finally — DONE

### FINDING-2 (High) — AutoDiscovery socket.receive()
- **File:** `shared-core/.../discovery/AutoDiscovery.kt:64`
- **Issue:** Only catches SocketTimeoutException, other exceptions propagate
- **Fix:** ✅ Added catch-all with break — DONE

### FINDING-3 (High) — Force unwrap
- **File:** `accessibility-receiver/.../InputBridgeAccessibilityService.kt:190`
- **Issue:** `currentStrokePath!!` — force unwrap that could NPE
- **Fix:** ✅ Changed to `val path = currentStrokePath ?: return` — DONE

### FINDING-4 (Medium) — AutoDiscovery socket leak
- **File:** `shared-core/.../discovery/AutoDiscovery.kt:29-46`
- **Issue:** No finally block to close DatagramSocket
- **Fix:** ✅ Added finally block — DONE

### FINDING-5 (Medium) — performGlobalAction
- **File:** `accessibility-receiver/.../InputBridgeAccessibilityService.kt:214-217`
- **Issue:** `performGlobalAction()` without try-catch
- **Fix:** ✅ Wrapped in runCatching — DONE

### FINDING-6 (Medium) — UsbInputCapture
- **File:** `input-capture/.../UsbInputCapture.kt:70,358`
- **Issue:** `getSystemService(USB_SERVICE) as UsbManager` unsafe cast
- **Fix:** ✅ Changed to `as?` with null fallback — DONE

### FINDING-7 (Low) — AutoDiscovery charset
- **File:** `shared-core/.../discovery/AutoDiscovery.kt:65`
- **Issue:** `String()` without explicit charset
- **Fix:** ✅ Added Charsets.UTF_8 — DONE

## Status
- All 19 issues found
- All 19 issues fixed
- Commit pushed: `6893041` (lambda fix) + previous commits
- CI: Was green on `6893041`, current build pending
- ADB connected to: `10.62.76.140:39571`
