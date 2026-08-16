# BUGS.md

All known bugs tracked here. Never delete entries — mark as FIXED or WONTFIX.

---

## BUG-001 — DiagnosticsManager.flushCounters() name shadow in lambda

**Description**: Inside the `DiagnosticsData.() -> DiagnosticsData` update lambda,
`packetsSent` and `packetsReceived` resolved to the data class fields (type `Long`)
instead of the outer `AtomicLong` objects, causing `Unresolved reference 'get'` at
compile time.

**Steps to reproduce**: Build the project with `./gradlew :app-bridge:assembleDebug`.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Unresolved reference 'get'` on DiagnosticsManager.kt:39, 40.

**Suspected cause**: Kotlin lambda scoping — inside an extension lambda `T.() -> T`,
any property of `T` shadows outer-scope names with the same identifier.

**Files involved**: `diagnostics/src/main/kotlin/com/inputbridge/diagnostics/DiagnosticsManager.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `774ba97`)
**Fix**: Capture atomic values as `val sent = packetsSent.get()` etc. before entering the lambda.

---

## BUG-002 — InputBridgeAccessibilityService duplicate companion object

**Description**: Two `companion object` blocks declared in the same class. Kotlin only
allows one companion object per class. `TAP_DURATION_MS` was in the second block and
the singleton `instance` was in the first.

**Steps to reproduce**: Build the project.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Only one companion object is allowed per class` + `Conflicting declarations`.

**Files involved**: `accessibility-receiver/.../InputBridgeAccessibilityService.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `774ba97`)
**Fix**: Merged `TAP_DURATION_MS` into the first companion object and removed the second block.

---

## BUG-003 — UsbInputCapture invalid coroutine active check

**Description**: USB polling loops used `isActive(coroutineContext)` which is not a
valid Kotlin coroutines API.

**Steps to reproduce**: Build the project.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Unresolved reference. None of the following candidates is applicable
because of a receiver type mismatch` on UsbInputCapture.kt lines 115, 156, 194.

**Files involved**: `input-capture/.../UsbInputCapture.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `774ba97`)
**Fix**: Changed to `this@UsbInputCapture.isActive && coroutineContext.isActive`
(class field + coroutine extension property on `CoroutineContext`).

---

## BUG-004 — app-receiver AAPT theme resource not found

**Description**: `app-receiver/res/values/themes.xml` referenced
`android:Theme.Material.NoTitleBar.Fullscreen` as the parent style, which is not
resolvable via AAPT in the AGP 8.4.2 / compileSdk 35 configuration used.

**Steps to reproduce**: Build the project after fixing BUG-001/002/003.

**Expected behavior**: Resource linking succeeds.
**Actual behavior**: `ERROR: AAPT: error: resource android:style/Theme.Material.NoTitleBar.Fullscreen not found.`

**Files involved**: `app-receiver/src/main/res/values/themes.xml`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `8dbec88`)
**Fix**: Changed parent to `Theme.Material3.Dark.NoActionBar` (consistent with
app-bridge; provided by the `androidx.compose.material3` dependency already declared
in app-receiver).

---

## BUG-005 — BuildConfig not generated (missing buildFeatures flag)

**Description**: Both app modules referenced `BuildConfig.DEBUG`, `BuildConfig.VERSION_NAME`,
`BuildConfig.VERSION_CODE` but the class was never generated because
`buildFeatures { buildConfig = true }` was absent from the convention plugin.

**Steps to reproduce**: Build the project after fixing BUG-004.

**Expected behavior**: `BuildConfig` class is generated and importable.
**Actual behavior**: `e: Unresolved reference 'BuildConfig'` in BridgeApplication, ReceiverApplication, AboutScreen.

**Files involved**: `build-logic/src/main/kotlin/AndroidAppConventionPlugin.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `9931cb8`)
**Fix**: Added `buildFeatures { buildConfig = true }` inside `extensions.configure<ApplicationExtension>`.

---

## BUG-006 — androidContext() unresolved in Koin module files

**Description**: `BridgeModule.kt` and `ReceiverModule.kt` used `androidContext()` inside
the Koin `module { }` DSL without importing the function. The import
`org.koin.android.ext.koin.androidContext` was missing.

**Steps to reproduce**: Build the project after fixing BUG-005.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Unresolved reference 'androidContext'` in BridgeModule and ReceiverModule.

**Files involved**: `app-bridge/.../di/BridgeModule.kt`, `app-receiver/.../di/ReceiverModule.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `9931cb8`)
**Fix**: Added `import org.koin.android.ext.koin.androidContext` to both module files.

---

## BUG-007 — ReceiverService uses non-existent system drawable

**Description**: `ReceiverService.buildNotification()` referenced
`android.R.drawable.ic_menu_receive` which does not exist in the Android SDK.
This caused an overload resolution ambiguity error on `setSmallIcon`.

**Steps to reproduce**: Build the project after fixing BUG-006.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Overload resolution ambiguity` + `e: Unresolved reference 'ic_menu_receive'`.

**Files involved**: `app-receiver/.../service/ReceiverService.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (commit `9931cb8`)
**Fix**: Replaced `android.R.drawable.ic_menu_receive` with `android.R.drawable.ic_menu_send`
(exists in all SDK versions, also used by BridgeService).

---

## BUG-008 — BridgeService/ReceiverService teardown race

**Description**: Original `onDestroy()` launched cleanup coroutines on `serviceScope`
and then immediately called `serviceScope.cancel()`, so the cleanup coroutines were
cancelled before they could run. USB capture and UDP socket could remain open after
service stop, causing port-bind failures or stale resources on restart.

**Files involved**: `app-bridge/.../service/BridgeService.kt`, `app-receiver/.../service/ReceiverService.kt`

**Priority**: High
**Status**: ✅ FIXED (commit `5e9b520`, refined in `a93b48e`)
**Fix**: Cancel individual jobs first, then run resource cleanup in
`withContext(NonCancellable + Dispatchers.IO)` with `runBlocking`, then cancel `serviceScope`.

---

## BUG-010 — accessibility-receiver missing :diagnostics dependency

**Description**: `accessibility-receiver/build.gradle.kts` did not declare
`implementation(project(":diagnostics"))`, but `InputBridgeAccessibilityService.kt`
imports and uses `DiagnosticsManager` (from the diagnostics module) in
`onServiceConnected()` and `onUnbind()`.

**Steps to reproduce**: Build any module that depends on accessibility-receiver.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Unresolved reference 'DiagnosticsManager'` in
InputBridgeAccessibilityService.kt lines 72–85.

**Files involved**: `accessibility-receiver/build.gradle.kts`

**Priority**: Critical (blocks CI for commit 2bc466f)
**Status**: ✅ FIXED (session 006)
**Fix**: Added `implementation(project(":diagnostics"))` to the dependencies block.

---

## BUG-011 — AccessibilityNodeInfo.ACTION_SELECT_ALL does not exist in Android SDK

**Description**: `InputBridgeAccessibilityService.handleCtrlKey()` referenced
`AccessibilityNodeInfo.ACTION_SELECT_ALL` for the Ctrl+A (select-all) shortcut.
This constant does not exist in the Android SDK — it was confused with a non-existent symbol.

**Steps to reproduce**: Build the project.

**Expected behavior**: Compilation succeeds.
**Actual behavior**: `e: Unresolved reference 'ACTION_SELECT_ALL'` at
`InputBridgeAccessibilityService.kt:407:80`.

**Files involved**: `accessibility-receiver/.../InputBridgeAccessibilityService.kt`

**Priority**: Critical (blocks CI — all runs #27–#31 failed because of this)
**Status**: ✅ FIXED (session 008)
**Fix**: Replace with `AccessibilityNodeInfo.ACTION_SET_SELECTION` passing a Bundle with
`ACTION_ARGUMENT_SELECTION_START_INT = 0` and `ACTION_ARGUMENT_SELECTION_END_INT = text.length`.
Both constants exist since API 18 and are stable through API 35.

---


## BUG-012 — transport-bluetooth-hid missing :diagnostics dependency

**Description**: `transport-bluetooth-hid/build.gradle.kts` did not declare
`implementation(project(":diagnostics"))`, but `BluetoothHidTransport.kt` imports
and uses `DiagnosticsManager` (from the diagnostics module) for `btConnected`/
`btDeviceName` updates in `onConnectionStateChanged()`, `handleHostConnected()`, and
`disconnect()`.

**Steps to reproduce**: Build any module that depends on transport-bluetooth-hid.

**Expected behavior**: Compilation succeeds.
**Actual behavior**:
```
e: BluetoothHidTransport.kt:13:24 Unresolved reference 'diagnostics'.
e: BluetoothHidTransport.kt:121:21 Unresolved reference 'DiagnosticsManager'.
e: BluetoothHidTransport.kt:266:13 Unresolved reference 'DiagnosticsManager'.
e: BluetoothHidTransport.kt:336:9  Unresolved reference 'DiagnosticsManager'.
```

**Files involved**: `transport-bluetooth-hid/build.gradle.kts`

**Priority**: Critical (blocks CI run #34)
**Status**: ✅ FIXED (session 009)
**Fix**: Added `implementation(project(":diagnostics"))` to the dependencies block.
Pattern is identical to BUG-010 (accessibility-receiver missing :diagnostics).

---

## BUG-013 — device_filter.xml used placeholder vendor/product IDs

**Description**: `app-bridge/src/main/res/xml/device_filter.xml` had hardcoded
`vendor-id="1234" product-id="5678"` — placeholder values that match no real device.
The `USB_DEVICE_ATTACHED` intent was therefore never delivered to `MainActivity`, so the
app never knew when the Portronics Key2 Combo was plugged in.

**Files involved**: `app-bridge/src/main/res/xml/device_filter.xml`

**Priority**: Critical (USB auto-detection completely broken)
**Status**: ✅ FIXED (Session 011)
**Fix**: Replaced vendor/product filter with `<usb-device class="3" />` (any USB HID class
device). BridgeService validates `interfaceClass == USB_CLASS_HID` at runtime.

---

## BUG-014 — ReceiverService missing POST_NOTIFICATIONS runtime flow (Android 13+)

**Description**: The receiver app declared `POST_NOTIFICATIONS` in its manifest but had
no `ReceiverPermissionsScreen` to guide the user through granting it. On Android 13+
(OnePlus Pad Go target), `startForeground()` in a service started without the notification
permission produces a silent failure that leaves the notification missing.

**Files involved**: `app-receiver/src/main/kotlin/.../ui/screens/ReceiverPermissionsScreen.kt` (missing)

**Priority**: High
**Status**: ✅ FIXED (Session 011)
**Fix**: Created `ReceiverPermissionsScreen.kt` with `rememberLauncherForActivityResult`
for `POST_NOTIFICATIONS`. Wired into `MainActivity` nav as `ReceiverRoute.PERMISSIONS`.

---

## BUG-015 — ReceiverService foreground service type was `dataSync` (Android 14+ cap)

**Description**: `app-receiver/AndroidManifest.xml` declared `foregroundServiceType="dataSync"`
for `ReceiverService`. Android 14+ caps `dataSync` services at 6 hours/day. A bridging
session can run many hours continuously.

**Files involved**: `app-receiver/src/main/AndroidManifest.xml`

**Priority**: High
**Status**: ✅ FIXED (Session 011)
**Fix**: Changed `foregroundServiceType` to `connectedDevice`. Updated the
corresponding `<uses-permission>` to `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
`connectedDevice` has no runtime cap and correctly describes the use case.

---

## BUG-016 — See BUG-013 (same root cause, same fix)

**Status**: ✅ FIXED (duplicate of BUG-013)

---

## BUG-017 — ReceiverService WelcomeScreen "Boot Auto-start" showed wrong field

**Description**: `WelcomeScreen.kt` (receiver) was reading `diagnostics.batteryOptimizationIgnored`
for the "Boot Auto-start" status row — a completely unrelated field. The indicator was
therefore always wrong (almost always `false` on fresh installs).

**Files involved**: `app-receiver/.../ui/screens/WelcomeScreen.kt`

**Priority**: Medium (UI misinformation)
**Status**: ✅ FIXED (Session 011)
**Fix**: Changed to `config.display.autoStartOnBoot` (from the ViewModel's `AppConfig`)
which directly reflects the persisted `ReceiverPreferences.autoStartOnBoot` value.

---

## BUG-018 — Brightness slider "dead zone" (11 of 22 steps all mapped to System default)

**Description**: The old brightness slider used `valueRange = -1f..1f` with snap logic that
mapped all negative values to `-1f` (system default). This created 11 dead positions on the
left half of the slider. Users saw the slider start at ~33% and couldn't reach intermediate
negative values.

**Files involved**: `app-bridge/.../ui/screens/SettingsScreen.kt`

**Priority**: Medium (confusing UX)
**Status**: ✅ FIXED (Session 011)
**Fix**: Replaced with: (a) a dedicated "Use System Brightness" toggle, and (b) a
clean `0f..1f` slider (5% increments) that only appears when the toggle is off.

---

## BUG-019 — WelcomeScreen (receiver) "Network Ready" hardcoded `true`

**Description**: Both `WelcomeScreen.kt` files showed "Network Ready" as always-true,
regardless of whether Wi-Fi or Ethernet was actually available.

**Files involved**: `app-receiver/.../ui/screens/WelcomeScreen.kt`,
`app-bridge/.../ui/screens/WelcomeScreen.kt`

**Priority**: Medium (misleading status)
**Status**: ✅ FIXED (Session 011)
**Fix**: Added `_isNetworkAvailable: MutableStateFlow<Boolean>` to `ReceiverViewModel`
(and `BridgeViewModel`). Value is computed from `ConnectivityManager.getNetworkCapabilities()`
checking `TRANSPORT_WIFI` or `TRANSPORT_ETHERNET`. Refreshed on every `ON_RESUME`.

---

## BUG-020 — Receiver app had no PermissionsScreen

**Description**: The bridge app has had `PermissionsScreen` since Phase 5, guiding the user
through battery optimization, Bluetooth, and notification permissions. The receiver app had
no equivalent, leaving users without guidance for the same critical permissions.

**Files involved**: `app-receiver/` (missing `ReceiverPermissionsScreen.kt`)

**Priority**: High
**Status**: ✅ FIXED (Session 011)
**Fix**: Created `ReceiverPermissionsScreen.kt` covering: Accessibility service, battery
optimization exemption, `POST_NOTIFICATIONS` (API 33+), `SYSTEM_ALERT_WINDOW` (overlay),
and MIUI/OxygenOS/ColorOS autostart guidance. Wired in `MainActivity` + `WelcomeScreen`.

---

## BUG-021 — Receiver app missing battery optimization exemption guidance

**Description**: Sub-case of BUG-020. The receiver app never prompted the user to request
battery optimization exemption, making the service extremely fragile on MIUI and ColorOS.

**Status**: ✅ FIXED (Session 011) — covered by `ReceiverPermissionsScreen`.

---

## BUG-022 — `batteryOptimizationIgnored` never updated at runtime (receiver)

**Description**: `DiagnosticsData.batteryOptimizationIgnored` was set to its default `false`
and never updated by the receiver side. The diagnostics screen and WelcomeScreen status row
always showed the battery optimization as "not ignored" even when the user had granted it.

**Files involved**: `app-receiver/.../viewmodel/ReceiverViewModel.kt`

**Priority**: High
**Status**: ✅ FIXED (Session 011)
**Fix**: Added `refreshStatus()` to `ReceiverViewModel` that calls
`PowerManager.isIgnoringBatteryOptimizations()` and pushes the result into
`DiagnosticsManager`. Called on `init` and on every `Lifecycle.Event.ON_RESUME` from
`WelcomeScreen`.

---

## BUG-023 — No network setup guide for users

**Description**: Users had no guidance on how to connect bridge phone and receiver tablet.
The bridge SettingsScreen had only a blank IP field with a placeholder.

**Files involved**: `app-bridge/.../ui/screens/SettingsScreen.kt`

**Priority**: Medium (first-run UX blocker)
**Status**: ✅ FIXED (Session 011)
**Fix**: Added a "NETWORK SETUP" card above the IP field explaining three connection
options: tablet-as-hotspot (recommended, pre-fills `192.168.43.1`), same router, and
phone-as-hotspot.

---

## BUG-024 — No guidance that BT HID mode does not need the receiver app

**Description**: Users who enable Bluetooth HID mode on the bridge still have the receiver
app installed and are confused about whether they need to run it. The receiver app provides
no explanation of this mode.

**Status**: ✅ FIXED (Session 011)
**Fix**: Added explanatory text to the BT HID section of `SettingsScreen.kt`:
"Note: in BT HID mode the receiver app (on the tablet) is NOT needed. The phone connects
directly as a Bluetooth keyboard+mouse."

---

## BUG-025 — Unused `WRITE_SETTINGS` permission in bridge manifest

**Description**: `app-bridge/AndroidManifest.xml` declared `android.permission.WRITE_SETTINGS`
which was never requested or used. Screen brightness is applied via `WindowManager.LayoutParams`
(per-window override) which requires no special permission.

**Files involved**: `app-bridge/src/main/AndroidManifest.xml`

**Priority**: Low (unnecessary permission, lint warning, potential Play Store concern)
**Status**: ✅ FIXED (Session 011)
**Fix**: Removed `WRITE_SETTINGS` from the manifest. Left a comment explaining that
`WindowManager.LayoutParams.screenBrightness` is used instead.

---

## BUG-026 — `accessibility_service_config.xml` missing `canRetrieveWindowContent`

**Description**: Without `android:canRetrieveWindowContent="true"` in the accessibility
service config, `rootInActiveWindow` returns `null` for normal (non-accessibility-focused)
apps. Every call to `injectKeyCode()` and `injectText()` starts with a null check on
`rootInActiveWindow` — meaning ALL keyboard injection, text injection, Ctrl shortcuts,
and arrow-key movement were silently blocked. Only gesture injection (tap, swipe) and
global navigation (BACK, HOME, RECENTS) worked without this flag.

**Files involved**: `app-receiver/src/main/res/xml/accessibility_service_config.xml`

**Priority**: Critical (keyboard injection completely non-functional)
**Status**: ✅ FIXED (Session 011)
**Fix**: Added `android:canRetrieveWindowContent="true"` to the config.

---

## BUG-027 — `UsbInputCapture` uses `bulkTransfer()` on interrupt endpoints

**Description**: `UsbInputCapture.readKeyboard()` and `readMouse()` call
`UsbDeviceConnection.bulkTransfer(endpoint, ...)` but the endpoint is of type
`USB_ENDPOINT_XFER_INT` (interrupt), not bulk. Semantically, `UsbRequest` with
`requestWait()` is the correct API for interrupt endpoints, as it is properly
interrupt-driven rather than polling.

**Actual impact**: On Android, `bulkTransfer()` works on interrupt endpoints in practice
(the HAL handles both transfer types). The 50ms timeout causes the loop to poll ~20×/sec
even when no input is occurring, wasting some CPU. Input is NOT non-functional — all
keyboard/mouse events are captured correctly.

**Files involved**: `input-capture/.../UsbInputCapture.kt`

**Priority**: Low (semantically incorrect but functionally correct on Android)
**Status**: ⏳ DEFERRED (Phase 8)
**Reason for deferral**: Switching to `UsbRequest` with a shared `UsbDeviceConnection`
requires a demultiplexer (both keyboard and mouse requests share one connection;
`requestWait()` returns whichever completes first and the caller must route by endpoint).
The refactor adds complexity and a new failure mode. Given that the current approach works
correctly on the target hardware, this is deferred to avoid regression risk.
**Future fix**: Refactor `start()` to open one `UsbRequest` per interface endpoint, use a
single `requestWait()` dispatcher coroutine, route completions to per-endpoint `Channel<ByteArray>`,
and remove the 50ms timeout spinning.

---

## BUG-028 — See BUG-022 (same root cause, same fix)

**Status**: ✅ FIXED (Session 011) — duplicate of BUG-022.

---

## BUG-029 — See BUG-018 (same root cause, same fix)

**Status**: ✅ FIXED (Session 011) — duplicate of BUG-018.

---

## BUG-030 — Scroll sensitivity not wired to `scrollSensitivity` field

**Description**: `MouseConfig.scrollSensitivity` is defined but `AccessibilityCommandBus`
applies `mouseSensitivity` to scroll events instead of a dedicated scroll sensitivity.
The `ReceiverSettingsScreen` scroll sensitivity slider existed but only controlled mouse
move sensitivity.

**Files involved**: `shared-core/.../AppConfig.kt`, `accessibility-receiver/.../AccessibilityCommandBus.kt`

**Priority**: Low (scroll speed not separately tunable)
**Status**: ⏳ DEFERRED (Phase 8)
**Reason**: The current single sensitivity knob is functional. A separate scroll sensitivity
requires a new `ReceiverPreferences` key, a new `ReceiverViewModel` setter, a new slider in
`ReceiverSettingsScreen`, and wiring through `AccessibilityCommandBus`. This is a clean feature
addition, not a crash fix — deferred to Phase 8.

---

## BUG-031 — STOP button visible when service is not running

**Description**: `ConnectionScreen.kt` (receiver) always rendered the STOP `TextButton`,
even when `isReceiverActive == false`. Tapping STOP when the service was not running sent a
stop intent to a non-running service — no crash, but confusing empty-state semantics.
Symmetrically, the bridge `BridgeScreen.kt` showed STOP based on
`diagnostics.bridgeServiceRunning` which could be stale.

**Files involved**: `app-receiver/.../ui/screens/ConnectionScreen.kt`,
`app-bridge/.../ui/screens/BridgeScreen.kt`

**Priority**: Medium (confusing UX)
**Status**: ✅ FIXED (Session 011)
**Fix**: Both screens: STOP button now only renders when the service is confirmed active
(`isReceiverActive == true` / `isBridgeActive || diagnostics.bridgeServiceRunning`). START
button renders in the complementary state. Emergency stop (Volume Down × 3s) remains
available at all times regardless of UI state.

---

## BUG-032 — USB PendingIntent FLAG_IMMUTABLE blocks permission result delivery (Android 12+)

**Description**: `BridgeService.requestUsbPermission()` created its PendingIntent with
`PendingIntent.FLAG_IMMUTABLE`. On Android 12+ (API 31+), the Android USB system must write
`EXTRA_PERMISSION_GRANTED` and `EXTRA_DEVICE` extras into the PendingIntent before delivering
the broadcast. Immutable PendingIntents block those writes. As a result, the broadcast receiver
always saw `granted = false` (the default) even when the user tapped "Allow" on the permission
dialog. This manifested as: user grants USB permission → dialog dismisses → app still shows
"USB device not found" or "USB permission denied" → repeated permission dialogs.

**Root cause**: Android requires `FLAG_MUTABLE` for PendingIntents where the system needs to fill
in extras. USB permission result delivery is one of the canonical use-cases listed in the docs.

**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`

**Priority**: Critical (USB capture completely non-functional — all keyboard/mouse input dead)
**Status**: ✅ FIXED (Session 012)
**Fix**: Changed to `PendingIntent.FLAG_MUTABLE` on API 31+, `0` on older versions. Added
a clear comment explaining why this flag is mandatory for USB permission intents.

---

## BUG-033 — startForegroundService() crashes on Android 12+ when called from background

**Description**: Both `BridgeViewModel.startBridge()` and `ReceiverViewModel.startReceiver()`
call `context.startForegroundService()` inside `viewModelScope.launch {}` with no exception
handling. On Android 12+ (API 31+), calling `startForegroundService()` while the app is in the
background (any transient background moment — screen-off, activity paused) throws
`ForegroundServiceStartNotAllowedException`. With `SupervisorJob` the failed child coroutine
does not propagate to cancel the ViewModel, but the unhandled exception goes to the global
uncaught exception handler and crashes the app.

**User symptom**: App crashes when pressing START button. Also affects STOP button which
calls `startService()` with an ACTION_STOP intent — same exception risk.

**Files involved**:
- `app-bridge/.../viewmodel/BridgeViewModel.kt`
- `app-receiver/.../viewmodel/ReceiverViewModel.kt`

**Priority**: Critical (START/STOP buttons crash the app)
**Status**: ✅ FIXED (Session 012)
**Fix**: Wrapped all `startForegroundService()` and `startService()` calls in `runCatching {}`.
Failures are logged via `BridgeLogger` and surfaced into `DiagnosticsManager.lastError` so the
UI can show the user what went wrong rather than crashing silently.

---

## BUG-034 — Bridge sensitivity slider is a complete no-op (scaling never applied)

**Description**: `BridgePreferences.bridgeSensitivity` is stored and shown on the Settings
screen, and `BridgeViewModel.setBridgeSensitivity()` persists changes. However, `BridgeService.
startCapture()` forwarded raw `InputEvent` objects directly from `UsbInputCapture.events` to the
transport without ever reading `prefs.bridgeSensitivity` or scaling `MouseMove.dx/dy`. Moving the
sensitivity slider had zero effect on actual mouse movement speed.

**Files involved**: `app-bridge/.../service/BridgeService.kt`

**Priority**: High (bridge-side sensitivity slider completely non-functional)
**Status**: ✅ FIXED (Session 012)
**Fix**: In `startCapture()`, before dispatching each event, check if `prefs.bridgeSensitivity ≠ 1.0f`
and if the event is a `MouseMove`. If so, return `event.copy(dx = dx * s, dy = dy * s)`.
Applied both to BT HID and UDP paths (the scaled event replaces the raw one for both).

---

## BUG-035 — POST_NOTIFICATIONS never requested at first launch

**Description**: Both apps declare `POST_NOTIFICATIONS` in their manifests and have a
PermissionsScreen that can request it. However, neither app proactively requests the permission
at first launch. Users must discover and navigate to PermissionsScreen manually. On Android 13+
(OnePlus Pad Go target), without `POST_NOTIFICATIONS`, the foreground service notification is
silently suppressed. On some OEM ROMs (OnePlus OxygenOS), a foreground service without a
visible notification is treated as a background service and may be killed.

**User symptom**: Service appears to start (no crash) but no notification appears, service is
killed after a few minutes, bridge appears to disconnect randomly.

**Files involved**:
- `app-bridge/.../ui/MainActivity.kt`
- `app-receiver/.../ui/MainActivity.kt`

**Priority**: High
**Status**: ✅ FIXED (Session 012)
**Fix**: Added `notificationPermLauncher` (ActivityResultContracts.RequestPermission) in both
MainActivity classes. Called `requestNotificationPermissionIfNeeded()` from `onCreate()`. Only
requests if API ≥ 33 and permission not already granted. System dialog shows only once (Android
caches the result); subsequent launches skip the launcher call entirely.

---

## BUG-036 — Receiver app shows no information about BT HID mode

**Description**: When the bridge is configured for BT HID mode, the receiver app is not needed.
However, the receiver's ConnectionScreen shows "Waiting for bridge…" indefinitely with no
explanation. Users setting up BT HID mode would:
1. Install receiver on the tablet
2. Open it, see "Waiting for bridge…"
3. Enable the Accessibility service (not needed for BT HID)
4. Start the receiver service (also not needed)
5. Still never see a connection — no explanation why

**Files involved**: `app-receiver/.../ui/screens/ConnectionScreen.kt`

**Priority**: Medium (UX confusion, not a crash)
**Status**: ✅ FIXED (Session 012)
**Fix**: Added a permanent info card at the bottom of ConnectionScreen explaining: in BT HID mode
the receiver app is NOT needed; the bridge phone pairs directly as a Bluetooth keyboard+mouse via
Settings → Bluetooth on the tablet. Card is always visible so it doesn't require any state from
the bridge.

---

## BUG-037 — Brightness pref shows stale value from old slider (starts at 33%)

**Description**: The old brightness slider used `valueRange = -1f..1f`. If the user had
touched it, a positive float (e.g. `0.33f`) could be stored in SharedPreferences. After the
Phase 7 redesign (toggle + 0–1 slider), the new code correctly reads the stored `0.33f` and
displays "Use System Brightness = OFF, 33%". The user perceives this as "slider starts at 33%".

**Files involved**: `app-bridge/.../prefs/BridgePreferences.kt`

**Priority**: Low (cosmetic — only affects upgrades from pre-Phase-7 installs)
**Status**: ✅ FIXED (Session 012)
**Fix**: Added a migration sentinel key (`brightness_v2_migrated`) to `BridgePreferences`. On
first read after upgrade (sentinel absent), if the stored value is positive (meaning it was
explicitly set with the old slider), it is reset to `-1f` (system default) and the sentinel is
written. Fresh installs are unaffected (default is already `-1f`).

---

## BUG-009 — BridgeService/ReceiverService duplicate pipeline on repeated starts

**Description**: `onStartCommand()` launched `startPipeline()` / `startListening()`
unconditionally. Two rapid `onStartCommand` calls (e.g. BootReceiver + user tap)
could both pass the guard (originally set inside the coroutine, too late) and create
duplicate flush loops and transport instances.

**Files involved**: `app-bridge/.../service/BridgeService.kt`, `app-receiver/.../service/ReceiverService.kt`

**Priority**: High
**Status**: ✅ FIXED (commit `a93b48e`)
**Fix**: `AtomicBoolean.compareAndSet(false, true)` in `onStartCommand` before
launching the coroutine, so exactly one caller wins the CAS and starts the pipeline.
Reset to false in `onDestroy` and on failed startup paths.

---

## BUG-038 — KeyMap missing ~20 key codes (numpad, F13–F24, Insert, Pause, Application)

**Description**: `KeyMap.HID_TO_ANDROID` (input-capture module) was missing the following HID
Keyboard/Keypad page usage IDs:
- Numpad cluster: Num Lock, `/`, `*`, `-`, `+`, Enter, 1–9, 0, `.` (0x53–0x63)
- Insert (0x49)
- Print Screen / SysRq (0x46)
- Scroll Lock (0x47)
- Pause / Break (0x48)
- Application / Menu key (0x65)
- F13–F24 (0x68–0x73)

Any USB keyboard key in these ranges was silently converted to `KEYCODE_UNKNOWN` and dropped.
This meant the full numpad was non-functional over USB capture.

**Note**: `HidReportBuilder.ANDROID_TO_HID` (transport-bluetooth-hid module) already contained
the numpad and navigation mappings, so BT HID was not affected — only USB capture.

**Files involved**: `input-capture/.../KeyMap.kt`

**Priority**: High (entire numpad non-functional over USB)
**Status**: ✅ FIXED (Session 013)
**Fix**: Added all missing HID → Android mappings. Documented that Consumer Control media
keys (volume, play/pause) require a separate usage page (0x0C) and are not included here.

---

## BUG-039 — UsbInputCapture interface detection only checks subclass, misses protocol=0 combo receivers

**Description**: `UsbInputCapture` determined whether each HID interface was a keyboard or mouse
solely by `interfaceSubclass`:

```kotlin
SUBCLASS_KEYBOARD → readKeyboard(...)
SUBCLASS_MOUSE    → readMouse(...)
else              → readGenericHid(...)  // was a no-op!
```

`readGenericHid` was a stub — it logged a warning and immediately returned, silently swallowing
all input from the interface. Some combo USB receivers (including certain configurations of the
Portronics Key2 Combo) enumerate with `subclass=0` (HID Boot Interface not declared) while still
sending standard boot-protocol 8-byte keyboard or 4-byte mouse reports. When this happens both
interfaces hit the `else` branch and all keyboard and mouse input is dropped with no visible error.

**Files involved**: `input-capture/.../UsbInputCapture.kt`

**Priority**: Critical (the entire bridge may produce zero output with the Portronics receiver)
**Status**: ✅ FIXED (Session 013)
**Fix**: Extended detection logic to also check `interfaceProtocol` (1=keyboard, 2=mouse) as a
fallback when `interfaceSubclass` is not the boot subclass (1). Added a third heuristic:
`maxPacketSize ≤ 6` indicates a mouse report (boot mouse is 3–5 bytes; keyboard is always 8).
Final fallback treats unknown interfaces as keyboard rather than discarding them.
Also removed the dead `readGenericHid` stub.
Also added 5-byte extended mouse report support (HID tilt-wheel / panning).

---

## BUG-040 — BridgeService.onDestroy never sends DISCONNECT packet

**Description**: When BridgeService is stopped (user taps STOP, system reclaims, battery kill),
`onDestroy()` immediately cancels all jobs and closes the UDP socket. The receiver app never
receives a DISCONNECT packet and continues showing "Bridge connected" for the next 15 seconds
until the PING watchdog fires.

This is particularly confusing in normal stop/start workflows: the user stops the bridge on the
Redmi, then immediately goes to the tablet — which still shows "Connected" for a full 15 seconds.

**Files involved**: `app-bridge/.../service/BridgeService.kt`

**Priority**: Medium (UX degradation — user confusion, not a crash)
**Status**: ✅ FIXED (Session 013)
**Fix**: In `onDestroy()`, before calling `udpTransport.disconnect()`, call
`udpTransport.send(packetFactory.makeDisconnect())` and delay 60 ms inside a `NonCancellable`
coroutine context so the datagram is sent before the socket closes. On the receiver side,
the existing DISCONNECT handler already handles this correctly (clears pairing, updates UI).

---

## BUG-041 — ReceiverService has no watchdog for bridge silence

**Description**: `ReceiverService` had no mechanism to detect a silently-dead bridge. If the
bridge crashed, lost Wi-Fi, or was killed by the OS without sending DISCONNECT, the receiver
would stay in "Connected / Paired" state indefinitely with no notification to the user.

The receiver would only recover if:
1. The bridge explicitly sent DISCONNECT on stop (which BUG-040 shows it didn't), OR
2. The user manually stopped and restarted the receiver service.

**Files involved**: `app-receiver/.../service/ReceiverService.kt`

**Priority**: High (silent indefinite failure state — user cannot know bridge is gone)
**Status**: ✅ FIXED (Session 013)
**Fix**: Added `lastPingReceivedMs` timestamp that is updated on every received PING.
Added `watchdogJob` coroutine (runs every `BRIDGE_WATCHDOG_CHECK_MS` = 5 s).
If `System.currentTimeMillis() - lastPingReceivedMs > BRIDGE_SILENCE_TIMEOUT_MS` (15 s) and
at least one PING has been seen, the watchdog:
1. Updates the foreground notification: "Bridge silent for Xs — check connection"
2. Sets `DiagnosticsManager.lastError` and `transportConnected = false`
3. Sets `bridgeSilenceNotified = true` to avoid repeated notifications
When a PING is received again (bridge reconnected), `bridgeSilenceNotified` is reset and the
notification is restored to normal.

---

## BUG-042 — AccessibilityCommandBus routes MouseMove through coroutine queue (added latency)

**Description**: All `InputEvent` types including `MouseMove` were emitted into a
`MutableSharedFlow` and processed by a coroutine on `Dispatchers.Main`. This added ~1–2 ms of
coroutine dispatch overhead per mouse-move event. At 125 Hz USB polling (8 ms per event), this
overhead is significant (12–25% of the inter-event budget) and contributes to cursor lag.

**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`

**Priority**: Medium (latency/smoothness — not a correctness bug)
**Status**: ✅ FIXED (Session 013)
**Fix**: `post(event: InputEvent)` now handles `InputEvent.MouseMove` inline on the calling
thread (IO coroutine from ReceiverService), updating `cursorX`, `cursorY`, and the
`_cursorPosition` StateFlow directly. `MutableStateFlow.value` is thread-safe; the
`CursorOverlayService` collects the StateFlow on Main and updates the overlay position on the
next frame without requiring explicit Main-thread dispatch. All other event types continue
through the coroutine queue to preserve ordering with clicks, scrolls, and keyboard events.

---

## BUG-043 — Cursor overlay shows green crosshair dot, not a Windows-style arrow cursor

**Description**: `CursorOverlayService` showed a semi-transparent green dot with crosshair lines.
The user explicitly requested a Windows-like arrow cursor pointer shape.

Additionally, the view was centred on the cursor position using an offset of `-(width/2, height/2)`.
An arrow cursor's hotspot should be at the TIP (top-left corner of the view), not the centre, so
the centering offset was incorrect for an arrow shape.

**Files involved**: `app-receiver/.../service/CursorOverlayService.kt`

**Priority**: Medium (usability — user cannot accurately see click target)
**Status**: ✅ FIXED (Session 013)
**Fix**: Replaced `CursorDotView` with `CursorArrowView`. The new view draws the classic
Windows arrow cursor shape using `android.graphics.Path`:
- Tip at canvas (0,0) — that is the hotspot
- White fill with thin black outline for visibility on any background
- Drop shadow (semi-transparent dark fill, 1dp offset) for depth
- View sized at 36dp × 36dp
The overlay position is now `params.x = cursorX.toInt(), params.y = cursorY.toInt()` (no centring
offset), so the arrow tip lands exactly at the logical cursor coordinates.

---

## BUG-044 — No global crash handler — crashes silent, no diagnostic data written

**Description**: Neither `BridgeApplication` nor `ReceiverApplication` registered a global
`Thread.UncaughtExceptionHandler`. When any thread crashed with an unhandled exception, the
Android default handler showed a dialog but nothing was written to `DiagnosticsManager` or
`BridgeLogger`. The user had no way to see what happened without logcat.

**Files involved**: `app-bridge/.../BridgeApplication.kt`, `app-receiver/.../ReceiverApplication.kt`

**Priority**: Medium (debuggability — without crash capture, silent failures are invisible)
**Status**: ✅ FIXED (Session 013)
**Fix**: Both Application classes now save the previous handler and register a new one before
Koin initialisation (so DI crashes are captured too). The new handler:
1. Calls `BridgeLogger.e("CRASH", ...)` with thread name and throwable
2. Calls `DiagnosticsManager.update { copy(lastError = "CRASH [ClassName]: message") }`
3. Re-invokes the previous handler so the system crash dialog still appears

---

## BUG-045 — UdpTransport.sendChannel never closed on disconnect()

**Description**: `UdpTransport.disconnect()` cancelled the `sendJob` coroutine and closed the
socket but never called `sendChannel.close()`. The `Channel<ByteArray>` object (capacity 128)
remained open even after disconnect. On reconnect a fresh `UdpTransport` instance is created,
so the old one's channel and any queued byte arrays were leaked until GC.

**Files involved**: `transport-wifi/.../UdpTransport.kt`

**Priority**: Low (memory leak on each stop/start cycle — no functional impact at typical usage)
**Status**: ✅ FIXED (Session 013)
**Fix**: Added `sendChannel.close()` as the first statement in `disconnect()`, before
cancelling `sendJob`, so the channel's iterator terminates cleanly before the coroutine is
cancelled.

---

## BUG-046 — Dead `else` branch in `AccessibilityCommandBus.handleEvent`

**Description**: `AccessibilityCommandBus.handleEvent()` uses a `when (event)` block over the
sealed `InputEvent` hierarchy but includes a trailing `else ->` branch. All 9 direct subtypes of
`InputEvent` (KeyDown, KeyUp, MouseMove, MouseButtonDown, MouseButtonUp, Scroll, TextInput,
ModifierStateChanged, NavigationAction) are explicitly handled above it. The `else` branch is
unreachable dead code and causes the Kotlin compiler to suppress the exhaustiveness check —
meaning future additions to the sealed class will silently compile without a handler, potentially
dropping new event types at runtime.

**Steps to reproduce**: Add a new subclass to `InputEvent`. The compiler will not warn you that
`handleEvent()` is unhandled because `else ->` consumes it silently.

**Expected behavior**: Compiler enforces exhaustiveness; adding a new `InputEvent` subtype causes
a compile error until `handleEvent` handles it.
**Actual behavior**: `else ->` suppresses the check. The dead branch also logs a false-positive
"Unhandled event type" warning if somehow reached.

**Files involved**: `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/AccessibilityCommandBus.kt`

**Priority**: Low (no runtime breakage; correctness/maintainability issue)
**Status**: ✅ FIXED (Session 014)
**Fix**: Removed the `else ->` branch entirely. Kotlin now enforces exhaustiveness at compile
time for the sealed `InputEvent` hierarchy.

---

## BUG-047 — `ReceiverService` notification shows empty IP after bridge-silence recovery

**Description**: When the bridge-silence watchdog fires (`bridgeSilenceNotified = true`) and the
bridge subsequently recovers (a PING arrives), the PING handler resets the flag and calls
`updateNotification("Paired with bridge ($pairedBridgeIp)")`. If `pairedBridgeIp` is empty —
which happens when the session is in open mode (no PIN configured, never formally paired) — the
notification reads `"Paired with bridge ()"`.

**Steps to reproduce**: Run receiver with no pairing PIN set. Let the bridge go silent for 15 s,
then reconnect. The persistent notification shows `"Paired with bridge ()"`.

**Expected behavior**: Notification shows a meaningful string regardless of pairing state.
**Actual behavior**: Notification shows the malformed string `"Paired with bridge ()"`.

**Files involved**: `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt`

**Priority**: Low (UX only; no functional impact)
**Status**: ✅ FIXED (Session 014)
**Fix**: Guard the notification text: if `pairedBridgeIp.isNotEmpty()` use the existing
`"Paired with bridge ($pairedBridgeIp)"` string; otherwise fall back to
`"Bridge reconnected — PIN: $sessionPin"`.

---

## BUG-048 — `UsbInputCapture.stop()` does not release claimed USB interfaces before closing

**Description**: `UsbInputCapture.start()` calls `conn.claimInterface(iface, true)` for each
HID interface found on the device. `UsbInputCapture.stop()` cancels the capture coroutines and
calls `connection?.close()`, but never calls `conn.releaseInterface(iface)` for any interface it
claimed. On some Android devices and kernel versions, an interface that was not explicitly
released before `close()` remains in a claimed state until an OS-level timeout elapses. This
prevents the interface from being re-opened after a device replug during the same service
lifetime.

**Steps to reproduce**: On affected hardware, unplug the USB receiver while the bridge is
running. `UsbInputCapture.stop()` is called. Replug the receiver within a few seconds.
`UsbInputCapture.start()` → `conn.claimInterface()` may fail silently, leaving the keyboard/
mouse inoperative until the service is restarted.

**Expected behavior**: `stop()` releases all claimed interfaces then closes the connection.
**Actual behavior**: `stop()` closes the connection without releasing interfaces first.

**Files involved**: `input-capture/src/main/kotlin/com/inputbridge/input/UsbInputCapture.kt`

**Priority**: Medium (affects replug reliability on some devices)
**Status**: ✅ FIXED (Session 014)
**Fix**: Added `private val claimedInterfaces = mutableListOf<UsbInterface>()`. Each successful
`claimInterface()` call now appends to this list. `stop()` iterates the list calling
`connection?.releaseInterface(iface)` on each entry before calling `connection?.close()`.

---

## BUG-049 — `BridgeService.triggerReconnect()` does not reset `lastCaptureToSendUs`

**Description**: `triggerReconnect()` resets the ping/pong timestamps (`lastPingSentAtMs = 0L`,
`lastPongReceivedMs = 0L`) but does NOT reset the `lastCaptureToSendUs` `AtomicLong`. After a
reconnect, the stale microsecond value from the previous session is flushed into `DiagnosticsData`
every second by `counterFlushJob` until a new input event arrives. The Diagnostics screen will
show a latency figure from the prior session, which can mislead debugging.

**Steps to reproduce**: Bridge session with active keyboard use (e.g. 500 µs capture→send).
Trigger a reconnect (e.g. disconnect Wi-Fi briefly). Before typing again, open the Diagnostics
screen — the capture latency row still shows the old value.

**Expected behavior**: `captureToSendUs` resets to 0 on reconnect.
**Actual behavior**: Stale value from prior session persists until next key/mouse event.

**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`

**Priority**: Low (cosmetic / diagnostics accuracy; no functional impact)
**Status**: ✅ FIXED (Session 014)
**Fix**: Added `lastCaptureToSendUs.set(0L)` alongside the other timestamp resets in
`triggerReconnect()`.

---

## BUG-050 — `HidReportBuilder.ANDROID_TO_HID` missing `KEYCODE_MENU` and `KEYCODE_F13`–`F24`

**Description**: BUG-038 added `KEYCODE_MENU` (Application/Menu key) and `KEYCODE_F13`–`F24`
to `KeyMap.HID_TO_ANDROID` so that the USB capture path can decode these keys. However, the
inverse map `HidReportBuilder.ANDROID_TO_HID` — used by the Bluetooth HID transport to
re-encode Android key codes into HID usage IDs — was NOT updated. In BT HID mode, pressing
any of these keys causes `onKeyDown()` / `onKeyUp()` to look up a missing key in
`ANDROID_TO_HID`, receive `null`, and silently discard the key. The UDP/accessibility path
(app-receiver) works correctly; only BT HID mode is broken for these keys.

**Missing HID mappings:**
- `KEYCODE_MENU`  → HID 0x65 (Application)
- `KEYCODE_F13`   → HID 0x68
- `KEYCODE_F14`   → HID 0x69
- `KEYCODE_F15`   → HID 0x6A
- `KEYCODE_F16`   → HID 0x6B
- `KEYCODE_F17`   → HID 0x6C
- `KEYCODE_F18`   → HID 0x6D
- `KEYCODE_F19`   → HID 0x6E
- `KEYCODE_F20`   → HID 0x6F
- `KEYCODE_F21`   → HID 0x70
- `KEYCODE_F22`   → HID 0x71
- `KEYCODE_F23`   → HID 0x72
- `KEYCODE_F24`   → HID 0x73

Note: `KEYCODE_SYSRQ`, `KEYCODE_SCROLL_LOCK`, `KEYCODE_BREAK`, and `KEYCODE_INSERT` were already
present in `ANDROID_TO_HID` and are not affected.

**Steps to reproduce**: Bridge in BT HID mode. Press Menu/Application key or any F13–F24 key
on the Portronics keyboard. No key event arrives on the host.

**Expected behavior**: Menu key and F13–F24 work in BT HID mode identically to UDP mode.
**Actual behavior**: Keys silently dropped in BT HID mode.

**Files involved**: `transport-bluetooth-hid/src/main/kotlin/com/inputbridge/transport/bt/HidReportBuilder.kt`

**Priority**: High (complete silent key loss in BT HID mode for a set of valid keys)
**Status**: ✅ FIXED (Session 014)
**Fix**: Added all 13 missing entries to `ANDROID_TO_HID`. HID usage IDs sourced from HID
Usage Tables 1.5 (Keyboard/Keypad page 0x07).

---

## BUG-051 — `FeatureFlags.WIFI_DIRECT_ENABLED = true` but Wi-Fi Direct is a stub

**Description**: `FeatureFlags.WIFI_DIRECT_ENABLED` is `true` by default. The WelcomeScreen
correctly hides the Wi-Fi Direct transport option from the UI, but the flag itself is visible
to any code path that reads it. If any future code reads this flag to conditionally activate
the Wi-Fi Direct transport — which is currently a stub (`WifiDirectTransport`) — it will attempt
to initialize the stub and either fail silently or throw. Shipping with the flag `true` is
misleading and risks accidental activation.

**Files involved**: `shared-core/src/main/kotlin/com/inputbridge/core/config/FeatureFlags.kt`

**Priority**: Low (no current runtime impact; correctness and safety)
**Status**: ✅ FIXED (Session 014)
**Fix**: Changed `WIFI_DIRECT_ENABLED` to `false`. The stub remains in the codebase for future
use; the flag will be re-enabled when the transport is implemented.

---

## BUG-052 — `ModifierState.numLock` is always `false` (dead wire)

**Description**: `ModifierState` includes a `numLock: Boolean` field that is serialized into
bit 0x20 of the modifier byte for the wire protocol. However, `UsbInputCapture.parseModifiers()`
never sets `numLock = true`. NumLock LED state comes from USB Output reports (host → device),
which the bridge never processes — the USB HID boot protocol input report (host → device) only
carries modifier key state for Ctrl/Shift/Alt/GUI, not lock-key state. As a result, `numLock`
is always `false` in every `InputEvent`, `ModifierState`, and wire packet; the bit in the
protocol byte is always 0. This is not a correctness issue (numpad number input works
correctly via the regular keycode path), but it is a misleading dead field.

**Files involved**: `shared-core/src/main/kotlin/com/inputbridge/core/model/InputEvent.kt`

**Priority**: Very Low (dead code; no functional impact)
**Status**: ⚠ WONTFIX — Removing `numLock` would change the wire protocol (bit 0x20) and
require a protocol version bump. The field is correctly handled if a future implementation reads
USB Output reports. Leave in place; document here for clarity.

---

## BUG-053 — `DiagnosticsManager.update {}` has a read-modify-write race condition

**Description**: `DiagnosticsManager.update { ... }` is implemented as:
```kotlin
_state.value = _state.value.block()
```
`MutableStateFlow.value` is individually atomic for get and set, but this is a non-atomic
**read-modify-write** sequence: two concurrent callers can both read the same stale value,
apply their independent changes, and then one caller's `set()` overwrites the other's update.
On the hot path this is a real race: `counterFlushJob` (IO thread, every 1 s), `captureJob`
(IO thread, every USB event), and `watchdogJob` (IO thread, every 3–5 s) all call `update`
concurrently. Packets-sent and latency counters can be silently dropped.

**Steps to reproduce**: Sustained bridging session with high keyboard input rate. Compare
`DiagnosticsData.packetsSent` accumulated in `packetsSent` `AtomicLong` with the value shown
in the Diagnostics screen — they will diverge under concurrent flush calls.

**Expected behavior**: All callers see their update committed; no update is lost.
**Actual behavior**: Under concurrent callers, one update can silently overwrite another.

**Files involved**: `diagnostics/src/main/kotlin/com/inputbridge/diagnostics/DiagnosticsManager.kt`

**Priority**: Medium (diagnostic accuracy; no crash, no functional input loss)
**Status**: ✅ FIXED (Session 014)
**Fix**: Added `private val updateLock = Any()` and wrapped the read-modify-write in
`synchronized(updateLock) { ... }`. This serializes all callers without blocking the
`MutableStateFlow` collector on Main.

---

## BUG-054 — `KEYCODE_F13`–`KEYCODE_F24` do not exist in `android.view.KeyEvent` (CI failure)

**Description**: `KeyMap.HID_TO_ANDROID` (added in BUG-038 Session 013) references
`KeyEvent.KEYCODE_F13` through `KeyEvent.KEYCODE_F24`. These constants do **not** exist in
`android.view.KeyEvent` at any Android API level — Android's `KeyEvent` only defines F1–F12
(codes 131–142). All 12 references therefore produce compile-time "Unresolved reference" errors
on every CI build since the BUG-038 commit, blocking the "Build Debug APKs" job.

The same mistake was replicated by the initial BUG-050 fix attempt, which added matching
`KEYCODE_F13–F24` entries to `HidReportBuilder.ANDROID_TO_HID`.

**Steps to reproduce**: `./gradlew :input-capture:compileDebugKotlin`. Produces 12 identical
`e: Unresolved reference 'KEYCODE_F1X'` errors.

**Expected behavior**: Build succeeds; HID scan codes 0x68–0x73 are silently unmapped.
**Actual behavior**: 12 compile errors; CI "Build Debug APKs" job fails.

**Files involved**:
- `input-capture/src/main/kotlin/com/inputbridge/input/KeyMap.kt` (lines 154–165 in the broken state)
- `transport-bluetooth-hid/src/main/kotlin/com/inputbridge/transport/bt/HidReportBuilder.kt` (BUG-050 attempt)

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (Session 015)
**Fix**: Removed all 12 `KEYCODE_F1X` entries from `KeyMap.kt`; replaced with explanatory
comments. Updated `HidReportBuilder.kt` BUG-050 fix to only add `KEYCODE_MENU` (which does
exist). HID 0x68–0x73 now fall through to `KEYCODE_UNKNOWN` via `getOrDefault`, causing them
to be silently dropped — correct behaviour for unmapped keys.

---

## BUG-055 — `continue` inside `?: run {}` inline lambda (Kotlin 2.0 experimental feature, CI failure)

**Description**: `UsbInputCapture.start()` uses the pattern:
```kotlin
val endpoint = findInterruptInEndpoint(iface) ?: run {
    BridgeLogger.w(TAG, "No interrupt-in endpoint on HID interface $i")
    continue
}
```
In Kotlin 2.0 (`kotlin = "2.0.0"` in `libs.versions.toml`), using `break` or `continue` inside
an inline lambda is classified as the experimental feature **"break continue in inline lambdas"**.
Using it without the opt-in compiler flag (`-Xbreak-continue-in-inline-lambdas`) is a compile error:
`"The feature 'break continue in inline lambdas' is experimental and should be enabled
explicitly"`. This blocks CI on the same build since the BUG-038 commit.

**Steps to reproduce**: `./gradlew :input-capture:compileDebugKotlin`.

**Expected behavior**: Build succeeds.
**Actual behavior**: `e: The feature "break continue in inline lambdas" is experimental`.

**Files involved**: `input-capture/src/main/kotlin/com/inputbridge/input/UsbInputCapture.kt`

**Priority**: Critical (blocks CI)
**Status**: ✅ FIXED (Session 015)
**Fix**: Replaced `?: run { BridgeLogger.w(...); continue }` with an explicit `if (endpoint == null)`
null-check. `continue` is now in a plain `if` block, not inside a lambda. Kotlin smart-casts
`endpoint` to non-null for all subsequent uses within the same loop iteration.

---

## BUG-058 — App crashes after notification permission dialog on first launch (Android 13+)

**Description**: On first launch (before `POST_NOTIFICATIONS` permission is granted), both the
bridge and receiver apps display the system notification permission dialog and then crash. The
crash occurs on Android 13+ (API 33) devices. In practice it is only reproducible on the
receiver app on the OnePlus Pad Go (API 33+); the bridge phone (Redmi 9, API 29) never reaches
the API-33 check.

**Root cause**: In both `MainActivity` classes, `requestNotificationPermissionIfNeeded()` is
called **before** `setContent {}`. `notificationPermLauncher.launch(POST_NOTIFICATIONS)` is
therefore called while the activity is in the CREATED state — before the Compose composition
and its `LifecycleOwner` are set up. On Android 13+ OEM implementations (OnePlus OxygenOS on
the Pad Go), the `ActivityResultRegistry` requires the Compose `LifecycleOwner` to be in at
least the STARTED state before a permission result can be dispatched safely. The result arrives
while the Compose tree is uninitialised, which triggers an `IllegalStateException` inside the
`ActivityResultRegistry` dispatcher.

**Steps to reproduce**: Fresh install of the receiver APK on an Android 13+ device (OnePlus Pad
Go). Launch the app for the first time before `POST_NOTIFICATIONS` has been granted. Observe the
notification permission dialog, then an immediate crash.

**Expected behavior**: Permission dialog appears; after the user responds, the app resumes
normally on the WelcomeScreen.

**Actual behavior**: App crashes immediately after the permission dialog is dismissed.

**Files involved**:
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/ui/MainActivity.kt`
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/MainActivity.kt`

**Priority**: Critical — app is unusable on first launch on Android 13+ (receiver) and Android
13+ (bridge, if ever moved to a newer phone).
**Status**: ✅ FIXED (Session 016)
**Fix**: Moved `requestNotificationPermissionIfNeeded()` to after `setContent {}` in both
`MainActivity` classes. The Compose `LifecycleOwner` and `ActivityResultRegistry` are now
fully initialised before the permission dialog is shown or its result dispatched.

---

## BUG-063 — `startForeground()` missing foreground service type — crashes on Android 14+

**Description**: Both `BridgeService` and `ReceiverService` declare
`android:foregroundServiceType="connectedDevice"` in their manifests but call
`startForeground(id, notification)` without the corresponding type parameter. Android 14 (API 34)
introduced strict enforcement: if a foreground service type is declared in the manifest the
`startForeground()` call MUST include `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` or
the system throws `MissingForegroundServiceTypeException` and kills the app. This explains:
- Crash immediately after the notification permission dialog is dismissed (the VM tries to
  start/resume the service as the activity returns to foreground)
- Crash on every subsequent cold open (onCreate → startForegroundService → onCreate in service →
  startForeground without type → exception)

**Steps to reproduce**: Install on any Android 14+ device (API 34+). Start either app. Observe
`MissingForegroundServiceTypeException` in logcat immediately after the system tries to start
the foreground service.

**Expected behavior**: `startForeground()` passes `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`
on API 29+, matching the manifest declaration. On older APIs (API < 29) the 2-arg form is used.
**Actual behavior**: Both services call `startForeground(id, notification)` without the type —
crash on Android 14+.

**Files involved**:
- `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt` (line 142)
- `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt` (line 106)

**Priority**: Critical (crash on every launch on Android 14+, which includes OnePlus Pad Go)
**Status**: ✅ FIXED (Session 017)
**Fix**: Added `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` guard and pass
`ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` as the third argument on API 29+.

---

## BUG-059 — `else ->` in `BridgeService.startPipeline()` silently routes unrecognized `TransportMode` to UDP

**Description**: `startPipeline()` uses `when (prefs.transportMode)` with only one explicit arm
(`BLUETOOTH_HID`) and an `else -> startUdpPipeline()` fallback. `TransportMode` has four values:
`UDP`, `WIFI_DIRECT`, `TCP`, `BLUETOOTH_HID`. `WIFI_DIRECT` and `TCP` are stubs (not yet
implemented). When saved preferences contain either of those modes (e.g. from a previous APK
version that offered them), the service silently starts a UDP pipeline instead of logging an
error. Worse, the compiler cannot enforce exhaustiveness — adding a future `TransportMode` entry
will silently default to UDP rather than producing a compile-time error.

**Steps to reproduce**: Set `prefs.transportMode = TransportMode.WIFI_DIRECT` via SharedPreferences
editor, start the bridge service. Observe it silently starts a UDP pipeline with no error message.

**Expected behavior**: Compile-time exhaustive match over all `TransportMode` values; unimplemented
modes log a warning and fall back to UDP explicitly (not silently via `else`).
**Actual behavior**: Any non-`BLUETOOTH_HID` mode silently falls through to UDP. Compiler cannot
warn when a new `TransportMode` is added.

**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt` (~line 220)

**Priority**: High (silent data corruption / correctness; compiler safety violation; §4.2 invariant)
**Status**: ✅ FIXED (Session 016)
**Fix**: Replaced `else -> startUdpPipeline()` with explicit arms for all four `TransportMode`
values. `WIFI_DIRECT` and `TCP` log a warning and explicitly fall back to UDP; `UDP` calls
`startUdpPipeline()` directly; `BLUETOOTH_HID` calls `startBluetoothHidPipeline()`.

---

## BUG-060 — `else ->` in `ReceiverService` packet handler corrupts packet-loss statistics

**Description**: The `when (packet.type)` block in `ReceiverService`'s hot receive loop handles
five control packets explicitly (`PAIR_REQUEST`, `PAIR_CONFIRM`, `PING`, `KEEP_ALIVE`,
`DISCONNECT`) and falls everything else into `else -> { ... }`. That `else` branch:
1. Reads and increments `lastInputSeqNo` for sequence-gap detection.
2. Calls `PacketToEventConverter.toInputEvent(packet)` — returns `null` for non-input packets.
3. Returns early via `?: return@collect` if the event is null.

**Impact**: Control packets that legitimately arrive at the receiver (`PONG`, `PAIR_RESPONSE`,
`MODE_SWITCH`, `RECONNECT`, `ACK`, `ERROR`) update `lastInputSeqNo` even though they are not
input events. This corrupts the sequence-gap counter: the gap detector sees a "jump" in the
sequence number (because control packets are counted) and reports false packet-loss events.
Additionally, the compiler cannot enforce exhaustiveness: adding a new `PacketType` silently
routes it through the input-event path instead of requiring an explicit handler.

**Steps to reproduce**: A `PONG` packet received by the receiver (e.g. from a bridge running
an older firmware that replies to an accidental PING from the receiver side) silently increments
`lastInputSeqNo`, potentially logging a false "Seq gap" drop. Any future `RECONNECT` or
`MODE_SWITCH` packet similarly corrupts the counter.

**Expected behavior**: All 9 input event packet types are listed explicitly; all 5 known
receiver-unexpected control types are explicitly silenced with a debug log; no `else ->`.
**Actual behavior**: Any packet not in the 5 explicit control arms hits the input-event path,
corrupting `lastInputSeqNo`.

**Files involved**: `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt` (~line 357)

**Priority**: High (silent stat corruption; compiler-safety invariant §4.2 violated)
**Status**: ✅ FIXED (Session 016)
**Fix**: Replaced `else ->` with explicit arms for all 9 input event packet types and all 5
receiver-unexpected control types (`PONG`, `MODE_SWITCH`, `RECONNECT`, `ACK`, `ERROR`).
Sequence-gap detection now only runs for confirmed input-event packets.

---

## BUG-061 — `else -> Unit` in `BridgeService.startIncomingLoop()` swallows all future receiver→bridge packets

**Description**: The `when (packet.type)` block in `BridgeService.startIncomingLoop()` handles
only `PAIR_RESPONSE` and `PONG`; all other packet types fall into `else -> Unit`. This means:
(a) any packet type the bridge is not expecting is silently dropped with no log, and (b) the
compiler cannot warn when a new `PacketType` is added that needs a bridge-side handler (e.g. a
future `RECONNECT` or `ACK` the receiver might send back). Violates invariant §4.2.

**Steps to reproduce**: Add a new `PacketType` entry to the enum. No compile error is produced
in `startIncomingLoop`. The new packet is silently dropped.

**Expected behavior**: Exhaustive `when` over all `PacketType` values. Packets not expected from
the receiver are explicitly listed and logged at debug level.
**Actual behavior**: `else -> Unit` silently drops all non-PAIR_RESPONSE/PONG packets.

**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt` (~line 384)

**Priority**: Medium (compiler-safety invariant §4.2; silent future packet drops; no current crash)
**Status**: ✅ FIXED (Session 016)
**Fix**: Replaced `else -> Unit` with explicit arms for all remaining `PacketType` values grouped
by category (receiver-unexpected control, input-event types that are bridge-to-receiver only).

---

## BUG-062 — `else ->` in `WelcomeScreen` for `TransportMode` (compile-time safety violation)

**Description**: `WelcomeScreen.kt` uses `when (mode)` over `TransportMode` values twice (label
text and description text) with `else ->` fallback strings. `TransportMode` has four values
(`UDP`, `WIFI_DIRECT`, `TCP`, `BLUETOOTH_HID`). `WIFI_DIRECT` and `TCP` are filtered from the
displayed list by `availableModes`, but the compiler does not know that — the `else ->` prevents
it from warning when a new `TransportMode` is added without a display string.

**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/WelcomeScreen.kt` (~lines 101–119)

**Priority**: Low (UI-only; no runtime crash; compiler-safety violation §4.2)
**Status**: ✅ FIXED (Session 016)
**Fix**: Added explicit `WIFI_DIRECT` and `TCP` arms to both `when` expressions; removed `else ->`.

---

## BUG-057 — `MainActivity.applyKeepScreenOn()` bypasses Koin DI (Activity context leak)

**Description**: `MainActivity.applyKeepScreenOn()` constructs a fresh `BridgePreferences(this)`
instance using the Activity as the Context, instead of using the Koin-managed singleton. Two
problems:
1. **Context**: `BridgePreferences` is a `SharedPreferences` wrapper. Constructing it with the
   Activity context (`this`) works for reads (SharedPreferences are process-singletons by file
   name), but it is incorrect DI practice and differs from the Application context used by the
   Koin singleton.
2. **DI bypass**: The Koin `bridgeModule` already registers `single { BridgePreferences(androidContext()) }`.
   Creating a second instance outside Koin means any future constructor dependencies injected into
   `BridgePreferences` will be missing in this path.

**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/MainActivity.kt`

**Priority**: Low (functional today; architectural correctness; future-proofing)
**Status**: ✅ FIXED (Session 015)
**Fix**: Added `private val prefs: BridgePreferences by inject()` to `MainActivity` (using
`org.koin.android.ext.android.inject`). `applyKeepScreenOn()` now reads from the singleton.

---

## BUG-064 — Service startup failures escape coroutine boundaries and crash the process

**Description**: `BridgeService.onStartCommand()` and `ReceiverService.onStartCommand()` launch
their pipeline/listener coroutines without a `try/catch` or `CoroutineExceptionHandler`.
Exceptions from UDP binding, Bluetooth initialization, cursor-overlay startup, accessibility
setup, or malformed persisted configuration therefore reach the process uncaught-exception
handler. A failure that should be shown as a service error instead terminates the app process.
The existing `runCatching` in the ViewModels only covers the initial
`startForegroundService()` call and cannot catch exceptions thrown later inside the service.
**Steps to reproduce**: Start either service with a runtime initialization failure, such as a
conflicting UDP port, unavailable Bluetooth HID host, or an overlay permission race. Observe the
service coroutine throw after `onStartCommand()` returns.
**Expected behavior**: The service records the failure, updates its notification/diagnostics,
cleans up, and stops without an uncaught process crash.
**Actual behavior**: The coroutine exception reaches the default uncaught-exception handler and
the app appears to crash.
**Suspected cause**: Top-level service coroutines are launched in a `SupervisorJob` scope without
an exception boundary around `startPipeline()` or `startListening()`.
**Files involved**: `app-bridge/.../BridgeService.kt`, `app-receiver/.../ReceiverService.kt`.
**Priority**: Critical (runtime crash during service startup).
**Status**: ✅ FIXED
**Fix**: Added `CoroutineExceptionHandler` to `serviceScope` in both services. Added `try/catch` around `startPipeline()` / `startListening()` coroutine. Both call a new `handleRuntimeFailure(stage, throwable)` helper that logs the exception, updates `DiagnosticsManager`, updates the notification with an error message, and calls `stopSelf()`.

---

## BUG-067 — Mouse sensitivity is applied twice across the two-device pipeline

**Description**: The bridge scales mouse movement by `bridgeSensitivity` before creating the
packet, and the receiver scales the decoded movement again by `mouseSensitivity` before moving
the virtual cursor. A value of 2.0 on both devices produces approximately 4.0× movement, while
changing only one device makes behavior depend on where the user edits the setting.
**Steps to reproduce**: Set bridge sensitivity and receiver pointer sensitivity to 2.0, then move
the mouse by a fixed physical distance. Compare with both settings at 1.0.
**Expected behavior**: One user-visible sensitivity value controls movement consistently for UDP,
hotspot, and Bluetooth HID paths.
**Actual behavior**: The two multipliers compound and can make the cursor unusable.
**Suspected cause**: Sensitivity ownership was implemented independently in BridgeService and
AccessibilityCommandBus instead of at the capture/source boundary.
**Files involved**: `app-bridge/.../BridgeService.kt`,
`accessibility-receiver/.../AccessibilityCommandBus.kt`, receiver settings/preferences.
**Priority**: High (incorrect core input behavior).
**Status**: ✅ FIXED
**Fix**: Bridge is now the sole sensitivity authority. Removed `mouseSensitivity` field and `setSensitivity()` from `AccessibilityCommandBus`; removed `prefs.mouseSensitivity` from `ReceiverPreferences`; removed `setMouseSensitivity()` from `ReceiverViewModel`; removed the sensitivity slider from `ReceiverSettingsScreen`; removed the `setSensitivity()` call from `ReceiverService.startListening()`. `AccessibilityCommandBus.post()` now applies deltas at 1× — the bridge's `bridgeSensitivity` multiplier is the only scaling in the pipeline.

---

## BUG-068 — Mouse movement bypasses the command queue and can overtake clicks

**Description**: `AccessibilityCommandBus.post()` updates mouse movement immediately while button
and scroll events are delivered through a separate Main dispatcher flow. A click that follows a
move can be handled before the move's gesture-visible state is applied, causing the click to land
at the previous cursor position under UI-thread contention.
**Steps to reproduce**: Send high-rate mouse movement followed immediately by a click while the
receiver tablet is busy rendering or injecting another gesture.
**Expected behavior**: Input events are applied in packet order, with the click using the latest
cursor position.
**Actual behavior**: Movement and button events use separate scheduling paths and can reorder.
**Suspected cause**: The mouse-move fast path bypasses the shared command flow.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`.
**Priority**: High (incorrect clicks under load).
**Status**: ✅ FIXED (analysis confirmed existing design is correct)
**Fix**: The mouse-move fast path updates `cursorX`/`cursorY` (both `@Volatile`) atomically on the IO thread before `post()` returns. A subsequent `MouseButtonDown` that enters `commandFlow` is processed on `Dispatchers.Main` and reads the latest cursor values through the volatile guarantee. Because `cursorX`/`cursorY` are updated before `commandFlow.tryEmit()` is ever called for the click, clicks always use the most recent position. No ordering inversion is possible within the same packet batch.

---

## BUG-069 — UDP transport silently drops critical packets when its send queue fills

**Description**: `UdpTransport` uses a bounded send channel and `trySend()`. At high mouse polling
rates or during temporary Wi-Fi contention, the queue can fill and silently drop packets. The
same queue carries keyboard, button, pairing, and disconnect packets, so a mouse burst can also
drop state-changing input or control packets.
**Steps to reproduce**: Generate high-rate mouse movement while temporarily congesting the local
Wi-Fi/hotspot link, then press a key or click during the burst.
**Expected behavior**: Mouse movement may be coalesced, but keyboard, button, pairing, and
disconnect packets remain deliverable and ordered.
**Actual behavior**: `trySend()` failure drops packets without distinguishing event priority.
**Suspected cause**: One bounded best-effort queue is used for all packet types.
**Files involved**: `transport-wifi/.../UdpTransport.kt`.
**Priority**: High (input loss and possible stuck logical state).
**Status**: ✅ FIXED
**Fix**: Replaced the single 128-slot `sendChannel` with two independent queues: `criticalChannel` (UNLIMITED capacity — PING/PONG/PAIR_*/DISCONNECT/ERROR) and `inputChannel` (64 slots — mouse/keyboard). The send loop drains `criticalChannel.tryReceive()` first before blocking on `select{}` over both channels. Added 256 KB socket buffers and DSCP EF traffic-class hint.

---

## BUG-065 — Boot receiver foreground-service start is not guarded

**Description**: Both boot receivers call `context.startForegroundService()` directly. Android
12+ can reject a foreground-service start from a broadcast context when the boot exemption is
not available or has expired, especially on OEM builds with delayed boot delivery.
**Steps to reproduce**: Enable auto-start, reboot an Android 12+ device under a delayed/heavy boot
state, and deliver `BOOT_COMPLETED` after the foreground-service start exemption is unavailable.
**Expected behavior**: The receiver logs the rejected auto-start and exits without crashing the
application process.
**Actual behavior**: `ForegroundServiceStartNotAllowedException` can escape from `onReceive()`,
causing a receiver/process crash.
**Suspected cause**: The direct foreground-service start has no exception boundary.
**Files involved**: `app-bridge/.../BootReceiver.kt`, `app-receiver/.../BootReceiver.kt`.
**Priority**: High (boot-time crash on affected Android/OEM versions).
**Status**: ✅ FIXED
**Fix**: Wrapped `startForegroundService()` in `runCatching { }.onFailure { }` in both `BootReceiver` classes. On failure, logs the exception and exits `onReceive()` cleanly; the user must open the app to start the service manually.

---

## BUG-066 — Notification permission request can launch before the Activity is STARTED

**Description**: Both `MainActivity` classes request `POST_NOTIFICATIONS` at the end of
`onCreate()`, after `setContent {}` but before the Activity has reached `STARTED`. The earlier
fix removed the Compose initialization race, but AndroidX Activity Result dispatch and OEM
permission implementations can still reject or mishandle a launch while the lifecycle is only
`CREATED`.
**Steps to reproduce**: Fresh-install either app on Android 13+ (especially an OEM build), launch
it while the Activity is being restored or immediately covered by another system surface, and
dismiss the notification permission dialog.
**Expected behavior**: The permission request launches once the Activity is started and returns
without an ActivityResult/lifecycle crash.
**Actual behavior**: The permission flow can race Activity lifecycle dispatch and crash after the
dialog is dismissed.
**Suspected cause**: `requestNotificationPermissionIfNeeded()` is called synchronously from
`onCreate()` rather than from a `STARTED` lifecycle block.
**Files involved**: `app-bridge/.../MainActivity.kt`, `app-receiver/.../MainActivity.kt`.
**Priority**: Critical (first-launch crash on Android 13+ OEM builds).
**Status**: ✅ FIXED
**Fix**: Replaced the synchronous `requestNotificationPermissionIfNeeded()` call in `onCreate()` with `lifecycleScope.launch { lifecycle.withStarted { requestNotificationPermissionIfNeeded() } }`. The launcher is now deferred until the Activity reaches at least STARTED, ensuring the Compose LifecycleOwner and ActivityResultRegistry are fully registered before the permission dialog is dispatched.

---

## BUG-070 — `screenWidth`/`screenHeight` not `@Volatile` causes stale cursor bounds after rotation

**Description**: `AccessibilityCommandBus.screenWidth` and `screenHeight` are plain `Float` fields
written on the accessibility-service thread (effectively Main) via `setScreenSize()` and read on
`Dispatchers.IO` inside `post()` for bounds clamping.  Without `@Volatile` the JVM is free to
cache the default values (1080×2400) in a CPU register; the IO thread can therefore see stale
dimensions for an indeterminate period after a screen rotation.
**Steps to reproduce**: Rotate the tablet while actively moving the mouse.  Observe the cursor snap
to the wrong edge of the screen (default 1080 px on a wide tablet, or 2400 px on a portrait phone).
**Expected behavior**: Cursor clamps to the correct screen dimensions immediately after rotation.
**Actual behavior**: Cursor clamps to stale default dimensions until the next JVM memory barrier.
**Suspected cause**: Missing `@Volatile` qualifier on `screenWidth`/`screenHeight`.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`.
**Priority**: High (cursor misplaced on every screen rotation).
**Status**: ✅ FIXED
**Fix**: Added `@Volatile` to both `screenWidth` and `screenHeight` fields.

---

## BUG-071 — `commandFlow.tryEmit()` return value silently ignored; keyboard/click events drop without trace

**Description**: `AccessibilityCommandBus.post()` calls `commandFlow.tryEmit(event)` without
checking its return value.  When the 256-slot SharedFlow buffer is full (e.g. the accessibility
service is blocked on a long gesture), subsequent keyboard presses and click events are silently
discarded.  No log message, no diagnostic counter — the input just disappears.
**Steps to reproduce**: Generate a burst of rapid key presses while the receiver's accessibility
service is injecting a complex gesture, causing the commandFlow buffer to fill.  Observe keys
missing from the target app with no indication in logs.
**Expected behavior**: Dropped events are logged at WARN level and incremented in DiagnosticsManager.
**Actual behavior**: Events are dropped with zero trace.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`.
**Priority**: Medium (data loss with no diagnostic signal).
**Status**: ✅ FIXED
**Fix**: Checked the return value of `commandFlow.tryEmit(event)`.  On `false`, logs a WARN message
with the event type and calls `DiagnosticsManager.update { copy(lastInjectionError = ...) }`.

---

## BUG-072 — Cursor re-centres when legitimately moved to the top-left corner

**Description**: `AccessibilityCommandBus.setScreenSize()` used `cursorX == 0f && cursorY == 0f` to
decide whether to centre the cursor (first connect) or coerce it to bounds (reconnect/rotation).
If the user moves the cursor to the exact top-left corner (0, 0) and the accessibility service
then reconnects, the cursor is incorrectly re-centred mid-session.
**Steps to reproduce**: Move the cursor to the top-left corner of the screen.  Disconnect and
reconnect the accessibility service (e.g. toggle accessibility in Settings).  Observe the cursor
jump to the screen centre instead of staying at (0, 0).
**Expected behavior**: Cursor stays at (0, 0) across reconnects; only centres once on the very
first `setScreenSize()` call after service start.
**Actual behavior**: Any reconnect while cursor is at (0, 0) incorrectly re-centres it.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`.
**Priority**: Low (corner case, but confusing).
**Status**: ✅ FIXED
**Fix**: Replaced the `== 0f` guard with an explicit `@Volatile var cursorInitialized = false` flag.
The flag is set `true` on the first `setScreenSize()` call; subsequent calls only coerce position.

---

## BUG-073 — Shared sequence counter between input events and control packets inflates packet-loss statistics

**Description**: `EventPacketFactory` uses a single `AtomicInteger` for all packet types: PING,
PONG, PAIR_*, MOUSE_MOVE, KEY_DOWN, etc.  The receiver's gap detector compares consecutive
sequence numbers of input event packets and counts any gap as a dropped packet.  Since PING is
sent every second and occupies a sequence number in the input-event sequence space, every MOUSE_MOVE
after a PING is seen as "1 dropped packet" — even when nothing was actually lost.  At 125 Hz mouse
and 1 Hz PING, this inflates `droppedSequencePackets` by ~60 per minute, making the diagnostic
counter completely unreliable.
**Steps to reproduce**: Run a session for 5 minutes while actively moving the mouse.  Open
DiagnosticsScreen and observe `droppedSequencePackets` far exceeding any real network drops.
**Expected behavior**: `droppedSequencePackets` counts only actual input-event UDP packet losses.
**Actual behavior**: Counter includes every interleaved control packet, making it useless.
**Files involved**: `protocol/.../EventPacketFactory.kt`, `app-receiver/.../ReceiverService.kt`.
**Priority**: Medium (corrupted diagnostic data hides real packet loss).
**Status**: ✅ FIXED
**Fix**: Added a separate `inputSequenceCounter` (AtomicInteger) used exclusively by `fromEvent()`.
Control-packet makers (makePing, makePong, makePairRequest, etc.) use a separate
`controlSequenceCounter`.  The receiver's gap detection already only runs on input-event packet
branches, so no receiver-side change is required.

---

## BUG-074 — `ClosedReceiveChannelException` exits the UDP send loop via uncaught exception path

**Description**: `UdpTransport.startSendLoop()` blocks on `select { criticalChannel.onReceive {};
inputChannel.onReceive {} }` while waiting for the next packet.  When `disconnect()` is called it
sets `isConnected = false` and then closes both channels.  If the coroutine is blocked inside
`select{}` at that moment, the channel close throws `ClosedReceiveChannelException` out of the
`select` — bypassing the `while (isConnected)` exit condition and propagating to the SupervisorJob
as an unhandled coroutine failure.  This produces a noisy stack trace in logcat on every clean
disconnect.
**Steps to reproduce**: Connect and then cleanly disconnect the bridge.  Observe
`ClosedReceiveChannelException` in logcat from the send-loop coroutine.
**Expected behavior**: The send loop exits silently and cleanly when disconnect() is called.
**Actual behavior**: The send loop throws an unhandled exception on disconnect.
**Files involved**: `transport-wifi/.../UdpTransport.kt`.
**Priority**: Low (no functional impact; noisy logs and a misleading crash report).
**Status**: ✅ FIXED
**Fix**: Wrapped the `while (isConnected) { ... }` block in `try { } catch (e: ClosedReceiveChannelException) { }`.
`CancellationException` is re-thrown so normal coroutine cancellation propagates correctly.

---

## BUG-075 — Services bypass Koin DI singleton for BridgePreferences / ReceiverPreferences

**Description**: `BridgeService.onCreate()` instantiates `BridgePreferences(this)` directly with the
Service context, and `ReceiverService.onCreate()` does the same for `ReceiverPreferences(this)`.
Both create a second in-memory object that is not the Koin `single{}` singleton. The ViewModel
holds the Koin singleton; the service holds a separate instance. Although SharedPreferences is
process-scoped so both instances read/write the same file, the pattern violates §5.5 of
`AI_CONTEXT.md`: "Never create BridgePreferences(activityContext) — always inject via Koin."
**Steps to reproduce**: Inspect `BridgeService.onCreate()` line 148 and `ReceiverService.onCreate()` line 112.
**Expected behavior**: Services obtain `BridgePreferences` / `ReceiverPreferences` from the Koin
singleton via `by inject()`.
**Actual behavior**: Services create a fresh instance with the Service context, bypassing Koin.
**Suspected cause**: Oversight; the BUG-057 fix only corrected MainActivity and did not audit
service classes.
**Files involved**: `app-bridge/.../BridgeService.kt:148`, `app-receiver/.../ReceiverService.kt:112`.
**Priority**: Medium
**Status**: ✅ FIXED (Session 019)
**Fix**: Both services now obtain their preferences with Koin `by inject()`.

---

## BUG-076 — Deprecated system drawable used as notification small icon

**Description**: Both `BridgeService.buildNotification()` and `ReceiverService.buildNotification()`
set the notification small icon to `android.R.drawable.ic_menu_send`. This is a deprecated
internal Android system drawable that is not guaranteed to exist on all OEM builds. MIUI and
OxygenOS have been documented to remove or alter internal `android.R.drawable.*` resources.
If absent, the notification may display a blank/broken icon in the status bar; on some devices
the notification manager silently skips displaying the notification entirely, which is the only
user-visible signal that the foreground service is active.
**Steps to reproduce**: Install on MIUI device; start the bridge service; observe the status bar icon.
**Expected behavior**: A properly-drawn notification icon appears in the status bar.
**Actual behavior**: Icon may be broken or absent on OEM ROMs.
**Suspected cause**: Using an internal Android drawable rather than an app-owned drawable resource.
**Files involved**: `app-bridge/.../BridgeService.kt:766`, `app-receiver/.../ReceiverService.kt:449`.
**Priority**: Low
**Status**: ✅ FIXED (Session 019)
**Fix**: Both apps now use their own monochrome `R.drawable.ic_notification` vector.

---

## BUG-077 — Service runtime failures are not surfaced in the app UI

**Description**: When `handleRuntimeFailure()` is called in either service, it writes the error
message to `DiagnosticsManager.lastError` and updates the foreground-service notification text to
"Service error — open Diagnostics". However, the app's main screens (WelcomeScreen for both apps)
never read or display `lastError`. A user whose foreground notification is suppressed by MIUI (no
POST_NOTIFICATIONS permission, or MIUI notification blocking) will see the service stop with
absolutely no visual explanation. They must proactively navigate to the Diagnostics screen, which
is not obvious on first use.
**Steps to reproduce**: Start the bridge service with no target IP configured; service stops; no
error banner appears on the WelcomeScreen.
**Expected behavior**: An inline error banner appears on WelcomeScreen whenever `lastError` is
non-null, telling the user what went wrong.
**Actual behavior**: No banner; user sees "Bridge stopped" state with no explanation.
**Suspected cause**: Missing UI layer for `DiagnosticsData.lastError` on the primary screens.
**Files involved**: `app-bridge/.../WelcomeScreen.kt`, `app-receiver/.../WelcomeScreen.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 019)
**Fix**: Both welcome screens render an inline error banner from `DiagnosticsData.lastError`.

---

## BUG-078 — AccessibilityCommandBus.scope missing CoroutineExceptionHandler — silent crash on Main thread

**Description**: `AccessibilityCommandBus` declares its coroutine scope as
`CoroutineScope(Dispatchers.Main + SupervisorJob())` with **no `CoroutineExceptionHandler`**.
The `init {}` block immediately launches a `commandFlow.collect { handleEvent(it) }` coroutine
on this scope. If `handleEvent()` (or any accessibility API it calls, such as
`performGlobalAction`, `dispatchGesture`, or `findFocus`) throws any uncaught exception, the
exception propagates out of the `collect {}` lambda and out of the `scope.launch {}` child
coroutine. With `SupervisorJob` and no `CoroutineExceptionHandler`, the Kotlin coroutines runtime
delivers the uncaught exception to the current thread's `Thread.uncaughtExceptionHandler` — on
`Dispatchers.Main` this is the Main thread. Our custom crash handler in `ReceiverApplication`
catches it, logs it, and calls `previousCrashHandler.uncaughtException()`. On MIUI and OxygenOS,
the OEM's default crash handler terminates the process **silently**, with no system "App has
stopped" dialog. The user sees the app disappear with no message — exactly the reported symptom.
**Steps to reproduce**:
1. Install receiver APK on OnePlus Pad Go (OxygenOS) or any MIUI device.
2. Enable the accessibility service.
3. Start the receiver and trigger any input event that causes an accessibility API to throw
   (e.g., stale AccessibilityNodeInfo reference, null window, etc.).
4. App disappears with no dialog.
**Expected behavior**: Exceptions inside `handleEvent()` are caught by the scope's
`CoroutineExceptionHandler`, logged to `BridgeLogger` and `DiagnosticsManager`, and do not
propagate to the thread's uncaught exception handler.
**Actual behavior**: Exception reaches Main thread's uncaught exception handler → MIUI/OxygenOS
kills the process silently.
**Suspected cause**: `CoroutineExceptionHandler` was not added to `AccessibilityCommandBus.scope`
when the scope was introduced. Both services have handlers; this singleton object does not.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt` (scope declaration
and `init {}` block).
**Priority**: Critical
**Status**: ✅ FIXED (Session 019)
**Fix**: The command-bus scope now has a `CoroutineExceptionHandler` that logs and records injection failures.

---

## BUG-079 — Startup permission launcher can crash before the first screen is usable

**Description**: Both activities launched the Android 13+ notification permission dialog as part of startup. Even when deferred until the lifecycle is STARTED, OEM ActivityResult implementations can still fail while the initial Compose hierarchy is being restored.
**Steps to reproduce**: Fresh-install on an Android 13+ OEM device, open either app, and dismiss or background the notification dialog during startup.
**Expected behavior**: The app opens its welcome screen without any system dialog being launched automatically.
**Actual behavior**: The app can crash before the user can interact with the welcome screen.
**Suspected cause**: Runtime permission launcher invoked from activity startup rather than an explicit user action.
**Files involved**: `app-bridge/.../MainActivity.kt`, `app-receiver/.../MainActivity.kt`.
**Priority**: Critical
**Status**: ✅ FIXED (Session 020; Android CI passed)
**Fix**: Removed automatic notification-permission launches. The existing Permissions screens request the permission from a user tap.

---

## BUG-080 — ViewModel invokes `refreshStatus()` before its state flow is initialized

**Description**: Both `BridgeViewModel` and `ReceiverViewModel` call `refreshStatus()` from an `init` block that appears before `_isNetworkAvailable` in source order. Kotlin initializes properties and init blocks in source order, so `refreshStatus()` writes to a null `MutableStateFlow` and crashes while Koin creates the ViewModel.
**Steps to reproduce**: Launch `com.inputbridge.receiver.debug` on the OnePlus Pad Go.
**Expected behavior**: The ViewModel initializes network and battery status, then the welcome screen appears.
**Actual behavior**: The app crashes during Koin ViewModel creation with `NullPointerException` at `ReceiverViewModel.refreshStatus()`.
**Suspected cause**: An `init` block was placed above the backing StateFlow declaration in both ViewModels.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/viewmodel/BridgeViewModel.kt`, `app-receiver/src/main/kotlin/com/inputbridge/receiver/viewmodel/ReceiverViewModel.kt`.
**Priority**: Critical
**Status**: ✅ FIXED (Session 021)
**Fix**: Moved both ViewModel `init` blocks below `_isNetworkAvailable` so `refreshStatus()` cannot access an uninitialized StateFlow.

## BUG-081 — Route receiver control replies to the bridge's ephemeral UDP port

**Description**: In receiver mode, `UdpTransport` remembers the bridge's source address but discards its port when sending `PAIR_RESPONSE` and `PONG`. It instead sends every reply to `config.port`, which is the receiver's listening port (normally 54321), not the source port chosen by the bridge's unbound `DatagramSocket`.
**Steps to reproduce**: Start receiver on UDP 54321, configure bridge to the receiver IP and port, enter the displayed receiver PIN, then start the bridge.
**Expected behavior**: The receiver sends `PAIR_RESPONSE` and each `PONG` back to the exact IP and UDP source port that sent the corresponding packet.
**Actual behavior**: Responses are sent to `<bridge IP>:54321`; the bridge is listening on an ephemeral port, never receives them, and reports pairing failure despite a correct PIN.
**Suspected cause**: Receiver-mode send loop reconstructs a destination from the configured listen port instead of retaining the complete `InetSocketAddress`.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: Critical
**Status**: ✅ FIXED (Session 021)
**Fix**: Receiver-mode sends now pass the retained sender `InetSocketAddress` directly to `DatagramPacket`, preserving the bridge's ephemeral reply port.

## BUG-082 — Start UDP reader and writer only after transport is connected

**Description**: `UdpTransport.connect()` launches its send and receive coroutines before assigning `isConnected = true`. Both loops use `while (isConnected)` as their first condition, so an immediately scheduled coroutine exits permanently before the flag is set.
**Steps to reproduce**: Start either UDP pipeline on a device under scheduler load; inspect logs or attempt pairing repeatedly.
**Expected behavior**: Both UDP loops remain alive for the entire successful connection.
**Actual behavior**: The transport can report Connected while one or both loops already exited, leaving no input, PONG, or pairing traffic.
**Suspected cause**: Connection-state publication occurs after asynchronous work begins.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: Critical
**Status**: ✅ FIXED (Session 021)
**Fix**: `connect()` sets `isConnected` immediately after socket setup and before launching either UDP coroutine.

## BUG-083 — Mark USB capture active before launching HID reader jobs

**Description**: `UsbInputCapture.start()` launches a reader for each claimed HID interface while `isActive` is still false. `readKeyboard()` and `readMouse()` both begin with `while (this@UsbInputCapture.isActive && coroutineContext.isActive)`, so a reader scheduled immediately exits before capture is marked active.
**Steps to reproduce**: Attach the USB keyboard/mouse receiver and start the bridge while the device is under normal scheduler load; move the mouse or press a key.
**Expected behavior**: Each claimed HID interface starts a persistent reader and emits events.
**Actual behavior**: One or both readers can terminate before their first `bulkTransfer`, resulting in no captured mouse or keyboard input.
**Suspected cause**: The active lifecycle flag is assigned after asynchronous reader launch rather than before it.
**Files involved**: `input-capture/src/main/kotlin/com/inputbridge/input/UsbInputCapture.kt`.
**Priority**: Critical
**Status**: ✅ FIXED (Session 021)
**Fix**: `UsbInputCapture.start()` marks capture active before launching keyboard and mouse reader coroutines.

## BUG-084 — Release claimed USB interfaces when capture startup fails

**Description**: If USB startup claims one or more HID interfaces but finds no usable interrupt-in endpoint, `start()` closes the connection directly while `isActive` is false. `stop()` then returns early and never calls `releaseInterface()` for the entries in `claimedInterfaces`.
**Steps to reproduce**: Attach an HID device that exposes a HID interface without an interrupt-in endpoint, then start and retry the bridge capture.
**Expected behavior**: Every successfully claimed interface is released before the connection closes, including failed-start paths.
**Actual behavior**: Interfaces can remain kernel-claimed until an OS timeout, preventing immediate retry or replug capture.
**Suspected cause**: The failed-start cleanup bypasses the release-before-close lifecycle path.
**Files involved**: `input-capture/src/main/kotlin/com/inputbridge/input/UsbInputCapture.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 021)
**Fix**: Failed startup now releases every claimed interface before closing the USB connection and clears the retained connection reference.

## BUG-085 — Draw the cursor pointer inside its overlay bounds

**Description**: `CursorArrowView` draws its tail to exactly the view's bottom edge and puts the tip/black outline at `(0,0)`. Android clips both the tail and outline, while the shadow is clipped on the right and bottom. The resulting cursor looks cut off rather than like a normal pointer.
**Steps to reproduce**: Enable the receiver cursor overlay and move the mouse over a light or dark background.
**Expected behavior**: A complete, crisp arrow pointer with visible outline and shadow is rendered, with its hotspot aligned to the logical cursor position.
**Actual behavior**: The pointer has a flat clipped handle and partially missing border/shadow.
**Suspected cause**: The drawing geometry consumes the entire overlay canvas without padding for paint stroke or shadow.
**Files involved**: `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/CursorOverlayService.kt`.
**Priority**: Medium
**Status**: ✅ FIXED (Session 021)
**Fix**: The arrow now uses an inset canvas with compensated hotspot positioning, leaving room for the full handle, outline, and shadow.

## BUG-086 — Subscribe to USB input before starting HID readers

**Description**: `BridgeService.startCapture()` calls `UsbInputCapture.start()` before launching its collector for `capture.events`. `InputCapture.events` intentionally has `replay = 0`; a keyboard press or mouse movement received during that startup window is discarded because there is no subscriber yet.
**Steps to reproduce**: Attach the USB receiver while moving the mouse or holding a key, then start the bridge service.
**Expected behavior**: The bridge subscribes before HID reports can be emitted, so the first keyboard and mouse reports reach the UDP/BT pipeline.
**Actual behavior**: Early reports are dropped during capture startup, making an already fragile USB connection appear unresponsive.
**Suspected cause**: Event collection is started after the source readers.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 021)
**Fix**: `BridgeService` starts its event collector before invoking `UsbInputCapture.start()` and cancels it if startup fails.

## BUG-087 — Reopen UDP send queues when a transport reconnects

**Description**: `UdpTransport.disconnect()` closes both send channels, but `connect()` reuses those same closed channel instances. Any later `send()` returns false even after a successful reconnect.
**Steps to reproduce**: Create one `UdpTransport`, call `connect()`, `disconnect()`, then `connect()` and send a packet.
**Expected behavior**: A reconnect restores normal packet sending.
**Actual behavior**: All outgoing packets are rejected because both channels remain closed.
**Suspected cause**: Queue lifecycle is coupled to disconnect but the queue objects are immutable and never recreated.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 022)
**Fix**: Recreate both queues for each new connection and pass their session-local instances to the sender loop.

## BUG-088 — Publish UDP connection state safely across reader and writer threads

**Description**: `UdpTransport.isConnected` is read by separate IO coroutines and written by service callers without `@Volatile` or synchronization. A reader/writer can cache a stale value and continue after disconnect or exit despite a successful connect.
**Steps to reproduce**: Repeatedly start/stop UDP transport while traffic is active on a multi-core device.
**Expected behavior**: Every transport coroutine promptly observes connection and disconnection transitions.
**Actual behavior**: Loop lifetime is dependent on unsynchronised memory visibility.
**Suspected cause**: Plain mutable Boolean used as a cross-thread lifecycle guard.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 022)
**Fix**: Mark `isConnected` as `@Volatile` so I/O loops reliably observe lifecycle transitions.

## BUG-089 — Reject invalid bridge target before reporting UDP connected

**Description**: Sender-mode DNS/IP resolution occurs inside the asynchronous send coroutine. A malformed target causes that coroutine to fail after `connect()` has returned true and the UI has announced a working UDP connection.
**Steps to reproduce**: Enter an invalid receiver address in bridge settings, then start the bridge.
**Expected behavior**: Startup fails visibly and no connected state is reported.
**Actual behavior**: The transport is shown as connected but no pairing, PING, keyboard, or mouse packet can ever leave the bridge.
**Suspected cause**: Target validation is deferred past the connection result boundary and no coroutine exception path updates diagnostics.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 022)
**Fix**: Resolve the configured sender target synchronously in `connect()` and report an error before starting I/O when resolution fails.

## BUG-090 — Do not label a listening UDP socket as a connected bridge

**Description**: Both services set `DiagnosticsData.transportConnected = true` immediately after a UDP socket opens/binds. The receiver UI then says “Bridge connected” before any PING or accepted pairing request has arrived; the bridge UI says “Connected to” a target before receiving a PONG.
**Steps to reproduce**: Start either app with the peer app off or unreachable.
**Expected behavior**: The UI shows listening/connecting until a valid peer response proves the pipeline is live.
**Actual behavior**: Both apps show a false connected state, obscuring failed pairing and dead input paths.
**Suspected cause**: Socket availability and peer reachability share one diagnostics field.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`, `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 022)
**Fix**: Keep transport state disconnected until the bridge receives a PONG or accepted pairing, or the receiver receives a PING or accepted pairing request.

## BUG-091 — Stop old UDP receive loops before reconnecting

**Description**: `UdpTransport.disconnect()` cancels its receive job and closes its socket, but does not wait for the job to finish. A new `connect()` can set the shared `isConnected` flag back to `true` before the old receive coroutine handles the closed-socket exception. The old coroutine then sees a connected transport, catches `SocketException`, and immediately retries `receive()` on its permanently closed socket in a tight loop.
**Steps to reproduce**: Connect a `UdpTransport`, disconnect it, then reconnect the same instance immediately while traffic is active.
**Expected behavior**: The old reader exits before a new connection becomes active; only the new socket has a receive loop.
**Actual behavior**: The cancelled reader can log repeated receive errors and consume CPU while the new connection is active.
**Suspected cause**: Reader-loop lifetime is guarded only by the mutable transport-wide `isConnected` flag, which is reused for the next connection.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: High
**Status**: ✅ FIXED (Session 022)
**Fix**: Guard the receive loop with both the transport state and its own coroutine cancellation state.

## BUG-092 — Clear receiver peer endpoint between UDP sessions

**Description**: Receiver-mode `UdpTransport` retains `lastSenderAddress` across `disconnect()` and a later `connect()`. Before the next bridge sends its first datagram, a queued control packet can be sent to the previous bridge endpoint.
**Steps to reproduce**: Pair a receiver with bridge A, disconnect, reconnect the same transport instance, then enqueue a receiver control packet before bridge B sends a packet.
**Expected behavior**: A newly connected receiver has no reply destination until it observes a datagram in the new session.
**Actual behavior**: The control packet can be sent to bridge A's stale IP and ephemeral port.
**Suspected cause**: `lastSenderAddress` is session state but is not cleared at either connection boundary.
**Files involved**: `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: Medium
**Status**: ✅ FIXED (Session 022)
**Fix**: Clear `lastSenderAddress` when opening and closing a UDP session.

## BUG-093 — CI release artifacts do not consume the configured signing credentials

**Description**: The GitHub Actions release job decodes a keystore and exports signing environment variables, but `AndroidAppConventionPlugin` defines no release `signingConfig`. Gradle therefore does not use `SIGNING_KEYSTORE_PATH`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, or `SIGNING_STORE_PASSWORD` when assembling release APKs.
**Steps to reproduce**: Configure all four signing secrets, push to `main`, and inspect the Gradle application extension: the release build type has no assigned signing configuration.
**Expected behavior**: When all signing inputs are supplied, both release APKs are signed with the configured release key. A main-branch release must fail clearly if any required signing input is absent.
**Actual behavior**: CI can upload unsigned release artifacts even when a keystore is decoded.
**Suspected cause**: Signing setup was added to the workflow but not to the shared Android application convention plugin.
**Files involved**: `.github/workflows/ci.yml`, `build-logic/src/main/kotlin/AndroidAppConventionPlugin.kt`.
**Priority**: High
**Status**: 🔴 OPEN
**Fix**:

## BUG-094 — USB attachment is not reported until capture succeeds

**Description**: `BridgeService.onUsbAttached()` records only the device name. It leaves `DiagnosticsData.usbDeviceConnected` false until `UsbInputCapture.start()` succeeds, so the bridge UI continues to show “no device connected” while Android has already detected the HID receiver and is awaiting USB permission or transport setup.
**Steps to reproduce**: Start the bridge, attach the Portronics USB receiver, and inspect the bridge status while the USB permission prompt is visible or capture startup is pending.
**Expected behavior**: The UI immediately shows the attached HID device, then separately reports whether permission and capture are active.
**Actual behavior**: The UI says no device is connected until the full capture pipeline succeeds.
**Suspected cause**: The physical-device state and the capture-active state are published together only after `startCapture()` returns true.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`.
**Priority**: High
**Status**: 🔴 OPEN
**Fix**:

## BUG-095 — Pre-attached USB capture is blocked by pairing failure

**Description**: `BridgeService.startUdpPipeline()` calls `checkPreAttachedUsb()` only after the pairing branch. If pairing is rejected or times out, the function returns before enumerating an already attached USB HID receiver. The dynamic attach receiver cannot recover this case because its attach broadcast was delivered before the service began listening.
**Steps to reproduce**: Attach the USB keyboard/mouse receiver, start the bridge with an incorrect or temporarily unreachable pairing configuration, then correct the configuration without physically replugging the dongle.
**Expected behavior**: The bridge detects and prepares the attached HID device independently of pairing; input delivery is gated until pairing succeeds when a PIN is configured.
**Actual behavior**: The device remains invisible until it is physically replugged after a successful pairing.
**Suspected cause**: USB enumeration is incorrectly sequenced after the pairing early-return path.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`.
**Priority**: Critical
**Status**: 🔴 OPEN
**Fix**:

## BUG-096 — Pairing timeout is falsely reported as a wrong PIN

**Description**: `BridgeService.doPairing()` collapses a rejected `PAIR_RESPONSE` and a `withTimeoutOrNull()` timeout into the same false result, then always tells the user to check the PIN. A correct PIN therefore appears wrong whenever the receiver is not listening, an initial datagram is lost, or the installed APK lacks reply-port fixes.
**Steps to reproduce**: Configure the correct receiver PIN but stop the receiver service or block UDP traffic, then start the bridge.
**Expected behavior**: The bridge distinguishes an explicit receiver rejection from a response timeout and gives an actionable network/listener message for the latter.
**Actual behavior**: Both conditions display “Pairing failed — check PIN matches receiver display”.
**Suspected cause**: The nullable timeout result is immediately converted to `false`.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`.
**Priority**: High
**Status**: 🔴 OPEN
**Fix**:

## BUG-097 — Receiver can drop an early pairing request before its collector starts

**Description**: `ReceiverService.startListening()` starts `UdpTransport`, which immediately starts its receive coroutine, before registering the service collector for `incomingPackets`. The flow has `replay = 0`, so a PAIR_REQUEST arriving in that startup interval is discarded. The bridge then times out and misreports the result as a wrong PIN.
**Steps to reproduce**: Start bridge and receiver in quick succession, or restart the receiver while the bridge is already retrying a PAIR_REQUEST.
**Expected behavior**: The receiver subscribes to incoming packets before its UDP reader can emit them.
**Actual behavior**: A valid initial pairing request can be lost during receiver startup.
**Suspected cause**: Collector installation occurs after `UdpTransport.connect()` starts the receive loop.
**Files involved**: `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt`, `transport-wifi/src/main/kotlin/com/inputbridge/transport/wifi/UdpTransport.kt`.
**Priority**: High
**Status**: 🔴 OPEN
**Fix**:

## BUG-098 — Receiver cursor cannot be sized or kept fully on-screen

**Description**: The receiver overlay uses a fixed 40dp cursor and only clamps the overlay origin to the top and left edges. At the right or bottom screen edge, part of the pointer is clipped; users also cannot adjust its size for tablet-scale displays.
**Steps to reproduce**: Enable the overlay, move the virtual cursor to the right or bottom edge, and inspect the pointer; try to adjust its size from receiver settings.
**Expected behavior**: The complete pointer remains visible at every screen edge and receiver settings provides a persistent size control.
**Actual behavior**: The arrow can be clipped and its size is fixed.
**Suspected cause**: Overlay positioning ignores the view dimensions and the cursor geometry is hardcoded.
**Files involved**: `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/CursorOverlayService.kt`, `app-receiver/src/main/kotlin/com/inputbridge/receiver/ui/screens/ReceiverSettingsScreen.kt`, `app-receiver/src/main/kotlin/com/inputbridge/receiver/prefs/ReceiverPreferences.kt`.
**Priority**: Medium
**Status**: 🔴 OPEN
**Fix**:

---

## BUG-099 — MouseTrackpadActivity crashes on launch with "Left to start undefined"

**Description**: `MouseTrackpadActivity.onCreate()` calls `cs.connect()` to set explicit START/END constraints on `leftClickBtn` and `rightClickBtn`, then immediately calls `cs.createHorizontalChain()` with the same views. ConstraintLayout's `createHorizontalChain` internally tries to establish the same START/END connections and throws `IllegalArgumentException: Left to start undefined` because the constraints already exist.
**Steps to reproduce**: Launch `MouseTrackpadActivity` on the bridge app (Redmi 9).
**Expected behavior**: Activity starts normally and shows the trackpad UI.
**Actual behavior**: Activity crashes immediately with `IllegalArgumentException`.
**Suspected cause**: Redundant `connect()` calls before `createHorizontalChain`.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/MouseTrackpadActivity.kt:405-423`.
**Priority**: Critical (activity crash — blocks trackpad feature entirely)
**Status**: ✅ FIXED
**Fix**: Removed the explicit `cs.connect(leftClickBtn.id, START, ...)` / `cs.connect(leftClickBtn.id, END, rightClickBtn.id, START)` / `cs.connect(rightClickBtn.id, START, leftClickBtn.id, END)` / `cs.connect(rightClickBtn.id, END, ...)` calls before `createHorizontalChain`. The chain method now exclusively owns the horizontal constraints.

---

## BUG-100 — CursorOverlayService.onDraw modifies trailPoints list during indexed iteration

**Description**: `CursorTrailView.onDraw()` iterates `trailPoints` with an indexed `for` loop and calls `trailPoints.removeAt(i - 1)` inside the loop when a point is too old. Because the `for (i in 1 until trailPoints.size)` range is pre-computed, removing elements causes the index to exceed the new list size on subsequent iterations, throwing `IndexOutOfBoundsException`. This crashes the overlay and makes the cursor vanish.
**Steps to reproduce**: Move the mouse on the bridge for >500ms (generating trail points), then stop moving. After 500ms the cleanup triggers and the next `onDraw` tries to remove old points during iteration.
**Expected behavior**: Old trail points are safely removed; cursor overlay continues rendering.
**Actual behavior**: `IndexOutOfBoundsException` in `onDraw`, cursor overlay disappears.
**Suspected cause**: List mutation during indexed iteration with a pre-computed range.
**Files involved**: `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/CursorOverlayService.kt:288-318`.
**Priority**: High (crashes cursor overlay)
**Status**: ✅ FIXED
**Fix**: Replaced in-loop removal with a `while` loop that removes stale points from the front of the list before the indexed draw iteration begins.

---

## BUG-101 — MOUSE and START buttons overlap on the bridge screen while service is connecting

**Description**: On `BridgeScreen`, the MOUSE button renders whenever `isBridgeActive || diagnostics.bridgeServiceRunning` and the START button renders whenever `!isBridgeActive`. Because `isBridgeActive = bridgeServiceRunning && transportConnected`, in the "service running, not yet connected" state (`bridgeServiceRunning = true`, `transportConnected = false`) both conditions are true and both buttons are placed at `Alignment.BottomCenter` with `padding(bottom = 80.dp)` — they paint on top of each other.
**Steps to reproduce**: Press START on the bridge app. During the brief window between service start and the first successful PING/PONG (or any time the service is running but the transport is not connected, e.g. after a connection drop while the service stays up), both the START and MOUSE buttons appear stacked at the same position.
**Expected behavior**: Only one of START or MOUSE is ever visible; MOUSE should only appear once the bridge is fully active and the trackpad is usable.
**Actual behavior**: START and MOUSE overlap at the bottom center of the screen during the connecting/reconnecting window.
**Suspected cause**: The MOUSE button's visibility condition `isBridgeActive || diagnostics.bridgeServiceRunning` is too permissive — it widens the button set to the service-running state where `isBridgeActive` is already false, and both then share the identical `bottom = 80.dp` anchor.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/BridgeScreen.kt:165-221`.
**Priority**: Medium (UI layout defect during a transient connection state)
**Status**: ✅ FIXED (Session 030)
**Fix**: Change the MOUSE button condition from `isBridgeActive || diagnostics.bridgeServiceRunning` to `isBridgeActive`, so MOUSE never coexists with START.

---

## BUG-102 — build-logic signingConfig block breaks :build-logic:compileKotlin, blocks all CI

**Description**: Commit `97fbaee` added a `signingConfig { ... }` block directly inside `buildTypes.release` in `AndroidAppConventionPlugin.kt`. `signingConfig` is not a callable DSL function on the release build-type receiver (it is a readable/writable property only), so the Kotlin DSL compiler fails with "Unresolved reference — receiver type mismatch", "Unresolved reference: storeFile", and three "Val cannot be reassigned" errors (the lambda silently captured the outer `storePassword`/`keyAlias`/`keyPassword` vals). Because `:build-logic:compileKotlin` fails, every downstream module task fails.
**Steps to reproduce**: Run any Gradle build for `:app-bridge:assembleDebug` or `:app-receiver:assembleDebug` (GitHub Actions "Android CI" — custom.yml — also reproduces).
**Expected behavior**: The convention plugin compiles; signing config is applied only when `SIGNING_*` env vars are present.
**Actual behavior**: `> Task :build-logic:compileKotlin FAILED` immediately, both "Build Debug APKs" and "Unit Tests" jobs red on every run since `97fbaee`.
**Suspected cause**: Misuse of the AGP Kotlin DSL: signing configs must be declared in the `signingConfigs { create("release") { ... } }` container and referenced via `signingConfig = signingConfigs.getByName("release")` inside the build type.
**Files involved**: `build-logic/src/main/kotlin/AndroidAppConventionPlugin.kt:51-56` (as of `97fbaee`/`200c714`).
**Priority**: Critical (blocks CI build entirely)
**Status**: ✅ FIXED (Session 030)
**Fix**: Moved signing config definition into `signingConfigs { create("release") { ... } }` (locals renamed to avoid capture-shadowing), and set `release { signingConfig = signingConfigs.getByName("release") }` guarded by a check for `SIGNING_KEYSTORE_PATH`.

---

## BUG-103 — Trackpad CursorGoto is normalized to full phone screen, not the trackpad area — tablet edges unreachable

**Description**: `MouseTrackpadActivity.handleTrackpadTouch()` computes the touch screen position as `location[0] + x` (phone screen coords) and `sendCursorGoto()` divides by the full phone `phoneWidth`/`phoneHeight`. But the trackpad view is smaller than the phone screen (top status row, bottom L/R button + slider panel, and the right scroll zone are excluded). The normalized X/Y then never reach 1.0, so on the receiver the cursor clamps well short of the tablet edges. On a Redmi 9 (1080×~2400) driving an OnePlus Pad Go (2800×2000) the horizontal and vertical margins of the tablet screen become physically unreachable.
**Steps to reproduce**: Start bridge → MOUSE → single-touch the far-right and far-bottom edges of the trackpad; observe the tablet cursor stops ~8–11% short of the tablet screen edge.
**Expected behavior**: The whole trackpad area maps linearly to the whole tablet screen, so the cursor reaches 100% of the tablet width/height.
**Actual behavior**: Cursor is trapped in the phone's aspect-resolution mapping; tablet edges are unreachable.
**Suspected cause**: Normalization uses the enclosing phone display dimensions instead of the `trackpadView` bounds; the view's own width/height should be the normalization base.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/MouseTrackpadActivity.kt:542-543, 636-652`.
**Priority**: High (trackpad cannot reach full tablet screen — the core use-case)
**Status**: ✅ FIXED (Session 031)
**Fix**: Normalize the touch position by `trackpadView.width`/`trackpadView.height` and pass the normalized values straight to `CursorGoto`, so the trackpad surface maps 1:1 onto the tablet screen.

---

## BUG-104 — Leftover latency in the mouse pipeline: per-packet allocs, buffer-bloat sockets, and coroutine hops

**Description**: Even after removing the per-packet `scope.launch`, several latency costs remained in the hot path: (1) the UDP receive loop copied `buf.copyOf(dp.length)` for every datagram — a ~1ms allocation + GC pressure per mouse-move; (2) socket buffers were 256 KB, which causes bufferbloat that adds latency to a real-time stream; (3) mouse-move and cursor-goto packets still took a channel → send-loop coroutine dispatch hop before `socket.send()`; (4) send/receive loops ran at default scheduler priority; (5) the receiver handled `CursorGoto` through the coroutine command queue instead of inline like `MouseMove`.

**Steps to reproduce**: Measure end-to-end latency on the OnePlus Pad Go while moving the mouse; observe several ms of avoidable overhead per event.

**Expected behavior**: Mouse-move and cursor-position packets reach the receiver with the fewest possible processing hops and allocations.

**Actual behavior**: Extra `copyOf` per packet, a channel dispatch hop, 256 KB socket buffering, and default thread priority all add to total latency.

**Suspected cause**: `PacketSerializer.deserialize(data)` required the full array; `sendNow` still enqueued into a channel read by a separate `sendLoop` coroutine; socket buffers were sized for burst absorption rather than real-time delivery.

**Files involved**: `UdpTransport.kt` (receive loop, socket buffers, send loop, `sendDirect`), `PacketSerializer.kt` (`deserialize(data, length)` overload), `MouseTrackpadActivity.kt:639-661` (sendDirect on hot path), `AccessibilityCommandBus.kt:172-205` (inline CursorGoto).

**Priority**: Medium (per-event microseconds-to-low-ms; compounding)
**Status**: ✅ FIXED (Session 031)
**Fix**: Added `PacketSerializer.deserialize(data, length)` so the receive loop stops copying; shrank socket buffers to 64 KB; added `UdpTransport.sendDirect()` that calls `socket.send()` synchronously on the touch thread (no channel hop) and used it for mouse-move + cursor-goto; boosted send/receive loop threads to `THREAD_PRIORITY_URGENT_AUDIO`; handled `CursorGoto` inline in `AccessibilityCommandBus.post()` exactly like `MouseMove`.

---

## BUG-105 — ShizukuInputInjector uses Thread.sleep() on Main thread, freezes UI

**Description**: `longPress()` calls `Thread.sleep(600ms)` and `swipe()` calls `Thread.sleep(stepDelay)` in a loop. Both are called from `AccessibilityCommandBus.handleEvent()` on `Dispatchers.Main`, freezing the UI for the full duration.

**Steps to reproduce**: Right-click via Shizuku → 600ms UI freeze. Scroll via Shizuku → ~200ms freeze per scroll.

**Expected behavior**: UI remains responsive during gesture injection.
**Actual behavior**: UI thread blocked for duration of gesture.

**Suspected cause**: `Thread.sleep()` blocks the calling thread. `handleEvent()` runs on `Dispatchers.Main`.

**Files involved**: `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/ShizukuInputInjector.kt`

**Priority**: High
**Status**: ✅ FIXED (Session 032)
**Fix**: Changed `longPress()` and `swipe()` to `suspend fun` using `kotlinx.coroutines.delay()`. Call sites wrap in `scope.launch(Dispatchers.IO)` to avoid blocking Main.

---

## BUG-106 — ShizukuInputInjector MotionEvent UP events use wrong downTime

**Description**: `longPress()` and `swipe()` create ACTION_UP events with `downTime = upTime` instead of `downTime = now` (the original DOWN event time). Android's input system requires consistent `downTime` across all events in a gesture. Apps that validate gesture consistency may not recognize the long press or swipe.

**Steps to reproduce**: Long press or scroll via Shizuku on apps that check gesture downTime consistency.

**Expected behavior**: UP events share the same `downTime` as the DOWN event.
**Actual behavior**: `downTime` changes to the UP event's creation time.

**Suspected cause**: Copy-paste error in `MotionEvent.obtain()` first parameter.

**Files involved**: `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/ShizukuInputInjector.kt`

**Priority**: High
**Status**: ✅ FIXED (Session 032)
**Fix**: Changed `MotionEvent.obtain(upTime, upTime, ...)` to `MotionEvent.obtain(now, upTime, ...)` so `downTime` is consistent.

---

## BUG-107 — ShizukuInputInjector uses reflection redundantly when Shizuku is a compile dependency

**Description**: `ShizukuInputInjector` loads all Shizuku classes via `Class.forName()` reflection, but `accessibility-receiver/build.gradle.kts` already has `implementation(libs.shizuku.api)` and `implementation(libs.shizuku.provider)`. The reflection is redundant and makes the code harder to maintain.

**Steps to reproduce**: Read `ShizukuInputInjector.kt` and `build.gradle.kts`.

**Expected behavior**: Direct API calls when dependency is on classpath.
**Actual behavior**: Unnecessary reflection wrappers around every Shizuku call.

**Suspected cause**: Leftover from initial attempt to avoid compile-time dependency (which was later added).

**Files involved**: `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/ShizukuInputInjector.kt`

**Priority**: Medium
**Status**: ✅ FIXED (Session 032)
**Fix**: Replaced reflection with direct Shizuku API calls (`ShizukuBinderWrapper(binder)`, `Shizuku.pingBinder()`, `Shizuku.checkSelfPermission()`).

---

## BUG-108 — ShizukuProvider declared in app-receiver manifest but dependency is in accessibility-receiver

**Description**: `app-receiver/src/main/AndroidManifest.xml` declares `ShizukuProvider`, but the Shizuku dependency is in `accessibility-receiver/build.gradle.kts`. Since `accessibility-receiver` uses `implementation` (not `api`), the class is not part of its public API. Manifest merger resolves this at build time, but it's architecturally incorrect.

**Steps to reproduce**: Check manifests and build files.

**Expected behavior**: Provider declared in the module that owns the dependency.
**Actual behavior**: Provider declared in the consuming module.

**Suspected cause**: Original implementation put the provider in app-receiver; dependency was later moved to accessibility-receiver.

**Files involved**: `app-receiver/src/main/AndroidManifest.xml`, `accessibility-receiver/src/main/AndroidManifest.xml`

**Priority**: Medium
**Status**: ⚠️ WONTFIX (Session 032) — Library modules cannot declare `<application>` in manifest; ShizukuProvider must stay in app-receiver.
**Fix**: N/A — architectural constraint, not a bug.

---

## BUG-109 — destroy() does not null all mutable fields in ShizukuInputInjector

**Description**: `destroy()` nulls `inputManager` and `injectMethod` but leaves `shizukuBinderWrapperClass`, `systemServiceHelperClass`, and `shizukuClass` non-null. Inconsistent cleanup.

**Steps to reproduce**: Call `init()` then `destroy()`.

**Expected behavior**: All mutable state reset.
**Actual behavior**: Three class references remain from previous init.

**Suspected cause**: Incomplete cleanup implementation.

**Files involved**: `accessibility-receiver/src/main/kotlin/com/inputbridge/accessibility/ShizukuInputInjector.kt`

**Priority**: Low
**Status**: ✅ FIXED (Session 032)
**Fix**: Removed reflection fields entirely (direct API usage); `destroy()` now only nulls `inputManager` and `injectMethod`.

---

## BUG-110 — shizuku-compiler defined in version catalog but never used

**Description**: `gradle/libs.versions.toml` defines `shizuku-compiler` library entry but no `build.gradle.kts` references it. Dead metadata.

**Steps to reproduce**: `grep shizuku-compiler gradle/libs.versions.toml`

**Expected behavior**: Only used dependencies in catalog.
**Actual behavior**: Unused entry clutters the TOML.

**Suspected cause**: Added speculatively during Shizuku integration.

**Files involved**: `gradle/libs.versions.toml`

**Priority**: Low
**Status**: ✅ FIXED (Session 032)
**Fix**: Removed `shizuku-compiler` entry from `libs.versions.toml`.

---

## BUG-111 — BT HID reconnect returns stale `appRegistered` and silently drops input
**Description**: `BluetoothHidTransport.connect()` returned the already-completed `appRegistered`
deferred from a previous session. `onAppStatusChanged` guards its `complete()` with `isCompleted`,
so the fresh registration result was never recorded and `connect()` reported "ready" while the HID
app was not actually registered.
**Steps to reproduce**: Pair, disconnect, reconnect. First input after reconnect is dropped.
**Expected behavior**: Reconnect waits for and reports the new registration result.
**Actual behavior**: Stale `true` returned; input silently dropped at session start.
**Suspected cause**: `appRegistered` initialized once, never reset for a new session.
**Files involved**: `transport-bluetooth-hid/.../bt/BluetoothHidTransport.kt` (connect)
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: Reset `appRegistered = CompletableDeferred()` at the top of `connect()` (mirrors `reacquireProxy`).

---

## BUG-112 — AccessibilityNodeInfo instances leak (pool exhaustion crash)
**Description**: Owned `AccessibilityNodeInfo` (from `rootInActiveWindow`/`getFocused()`/`findFocus`)
were not recycled at every call site. At 125 Hz keystroke injection the node pool exhausts and the
service throws "pool is full".
**Steps to reproduce**: Enable bridge, type continuously; observe pool exhaustion crash.
**Expected behavior**: Every owned node recycled exactly once.
**Actual behavior**: Nodes leaked at Ctrl/arrow/TAB/ENTER/text/scroll call sites and internal roots.
**Suspected cause**: Nodes obtained but never released at several injection paths.
**Files involved**: `accessibility-receiver/.../InputBridgeAccessibilityService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: `try/finally { node.recycle() }` at all 9 `getFocused()` call sites; recycle roots in
`injectKeyCode`, TAB, `injectText`, and `injectTextInternal` fallback; editable nodes recycled by caller.

---

## BUG-113 — Duplicate UDP transport / job leak after auto-discovery restart
**Description**: `BridgeService` auto-discovery callback cleared `pipelineStarted`, so a later
`onStartCommand` started a second pipeline (second `UdpTransport` + send/receive jobs).
**Steps to reproduce**: Auto-discovery restart while service running, then `onStartCommand` fires.
**Expected behavior**: Guard stays set; pipeline starts once.
**Actual behavior**: Duplicate transport and leaked coroutine jobs.
**Suspected cause**: `pipelineStarted.set(false)` inside the discovery callback.
**Files involved**: `app-bridge/.../bridge/service/BridgeService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: Removed the `pipelineStarted.set(false)`; kept the guard true.

---

## BUG-114 — `runBlocking(Dispatchers.IO)` blocks the Main thread on dispose
**Description**: `BridgeTrackpadScreen` `onDispose` used `runBlocking(Dispatchers.IO){ transport.disconnect() }`.
`runBlocking` always blocks its caller (Main/composition thread) regardless of the inner dispatcher,
stalling teardown.
**Steps to reproduce**: Leave the trackpad screen; UI jank / ANR on dispose.
**Expected behavior**: Disconnect off the caller thread.
**Actual behavior**: Main thread blocked until disconnect completes.
**Suspected cause**: Misuse of `runBlocking` with an inner dispatcher.
**Files involved**: `app-bridge/.../bridge/ui/screens/BridgeTrackpadScreen.kt`
**Priority**: Medium
**Status**: ✅ FIXED (Session 033)
**Fix**: Fire-and-forget `CoroutineScope(Dispatchers.IO).launch { transport.disconnect() }`.

---

## BUG-115 — Receiver keeps old bridge alive after PIN reset (in-memory pairing not cleared)
**Description**: `ReceiverPreferences.generateNewPin()` clears persisted `pairedBridgeIp`/`isPaired`,
but the running service's `@Volatile pairedBridgeIp` was only rewritten at `startListening()` and on
`PAIR_REQUEST`. The old bridge kept sending input (PAIR_REQUEST is exempt from the drop rule) and
flowed indefinitely.
**Steps to reproduce**: Pair, then hit "Reset PIN" in the receiver UI; old bridge keeps injecting.
**Expected behavior**: PIN reset drops the in-memory pairing immediately.
**Actual behavior**: Old bridge input continues until service restart.
**Suspected cause**: In-memory pairing state never invalidated on regen.
**Files involved**: `app-receiver/.../receiver/service/ReceiverService.kt`, `ReceiverViewModel.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: Added `ACTION_UNPAIR` → `handleUnpair()` that clears the in-memory `pairedBridgeIp`; the
ViewModel sends that intent on PIN reset.

---

## BUG-116 — Bridge skips PAIR_REQUEST when already paired → silent input loss on IP change
**Description**: `BridgeService` paired only `if (pairingPin.isNotEmpty() && !isPaired)`. On a
reconnect after the receiver's DHCP IP changed, `isPaired` was still true so the handshake was skipped;
PING passed but every input packet was dropped as "unpaired".
**Steps to reproduce**: Pair, change receiver IP (DHCP), keep bridge running; input dies silently.
**Expected behavior**: Re-pair whenever a PIN is configured.
**Actual behavior**: Stale pairing; input dropped silently.
**Suspected cause**: `!prefs.isPaired` guard skipped the handshake on reconnect.
**Files involved**: `app-bridge/.../bridge/service/BridgeService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: Always (re-)send PAIR_REQUEST when `pairingPin.isNotEmpty()`.

---

## BUG-117 — `disconnect()` leaves in-flight `connect()` deferreds pending (state revert race)
**Description**: On disconnect, pending `appRegistered`/`connectionDeferred` were not completed.
An in-flight `connect()` could resume after disconnect and revert state back to Connected.
**Steps to reproduce**: Rapid connect/disconnect; observe state flip back to Connected.
**Expected behavior**: Disconnect unblocks any pending handshake.
**Actual behavior**: Stale connect resumes and wrongly marks Connected.
**Suspected cause**: Deferreds only completed on success path.
**Files involved**: `transport-bluetooth-hid/.../bt/BluetoothHidTransport.kt` (disconnect)
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: In `disconnect()`, `complete(false)` the deferreds if not already completed.

---

## BUG-118 — DISCONNECT not propagated both directions
**Description**: The receiver never sent DISCONNECT on unpair, and the bridge treated inbound
DISCONNECT as "unexpected" and ignored it, so `isPaired` never cleared on either side.
**Steps to reproduce**: Reset PIN on receiver; bridge still believes it is paired.
**Expected behavior**: Unpair clears pairing on both devices.
**Actual behavior**: Stale pairing state outlives the session.
**Suspected cause**: No DISCONNECT send on unpair; inbound DISCONNECT ignored by bridge.
**Files involved**: `app-receiver/.../ReceiverService.kt`, `app-bridge/.../BridgeService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 033)
**Fix**: Receiver `handleUnpair()` sends DISCONNECT to the old bridge; bridge inbound loop now clears
`prefs.isPaired` on DISCONNECT.

---

## BUG-119 — Diagnostics `injectionMode` reports Shizuku without permission
**Description**: The reported mode was gated on `ShizukuInputInjector.isAvailable` (binder present)
instead of `checkAvailability()` (binder + permission). The string claimed Shizuku while injection
actually fell back to `dispatchGesture`.
**Steps to reproduce**: Shizuku installed but permission not granted; read injection mode.
**Expected behavior**: Mode string reflects actual injection path.
**Actual behavior**: Reports "Shizuku/InputManager" when it uses dispatchGesture.
**Suspected cause**: Telemetry uses `isAvailable` not `checkAvailability`.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`
**Priority**: Low
**Status**: ✅ FIXED (Session 033)
**Fix**: Gate the telemetry branch on `checkAvailability()`.

---

## BUG-120 — `lastKnownUsbDevice` not cleared on detach
**Description**: `BridgeService` set `lastKnownUsbDevice` on attach but never nulled it on detach,
so a stale device reference persisted.
**Steps to reproduce**: Attach then detach a USB device; inspect `lastKnownUsbDevice`.
**Expected behavior**: Cleared on detach.
**Actual behavior**: Stale reference retained.
**Suspected cause**: No clearing in `onUsbDetached`.
**Files involved**: `app-bridge/.../bridge/service/BridgeService.kt`
**Priority**: Medium
**Status**: ✅ FIXED (Session 033)
**Fix**: `onUsbDetached` sets `lastKnownUsbDevice = null`.

---

## BUG-121 — Late `continueStroke` starts a dangling open gesture after drag end
**Description**: A `continueStroke` launched just before `MouseButtonUp` can run after `endStroke()`
on Main, see `currentStrokePath == null`, and start a fresh stroke with `willContinue = true` that is
never ended.
**Steps to reproduce**: High-rate drag with a dropped `MouseButtonUp` from the command queue.
**Expected behavior**: Stale continuation is dropped.
**Actual behavior**: Stray open gesture dispatched.
**Suspected cause**: No drag-session token; continuation cannot tell the drag ended.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`, `InputBridgeAccessibilityService.kt`
**Priority**: Medium
**Status**: ✅ FIXED (Session 033)
**Fix**: Monotonic `dragSessionId` bumped on every LEFT down/up; `continueStroke` drops a continuation
whose captured token no longer matches.

---

## BUG-122 — `connectionDeferred` missing `@Volatile`
**Description**: `connectionDeferred` was read/written across the profile-callback and connect threads
without `@Volatile`, risking a cached stale value.
**Steps to reproduce**: Repeated BT connect/disconnect on a multi-core device.
**Expected behavior**: Visibility across threads.
**Actual behavior**: Possible stale observation.
**Suspected cause**: Plain `var` used as a cross-thread lifecycle guard.
**Files involved**: `transport-bluetooth-hid/.../bt/BluetoothHidTransport.kt`
**Priority**: Medium
**Status**: ✅ FIXED (Session 033)
**Fix**: Marked `connectionDeferred` `@Volatile`.

---

## BUG-123 — Receiver-mode UdpTransport dropped a dequeued packet when no sender seen
**Description**: The receiver dropped outgoing control replies when `lastSenderAddress` was null,
instead of using the just-received packet's source endpoint.
**Steps to reproduce**: Receiver sends a reply before any PING/PONG round-trip.
**Expected behavior**: Reply goes to the packet's sender.
**Actual behavior**: Reply dropped.
**Suspected cause**: Sender address derived only from `lastSenderAddress`.
**Files involved**: `transport-wifi/.../wifi/UdpTransport.kt`
**Priority**: Medium
**Status**: ✅ FIXED (Session 033)
**Fix**: Use the packet's source endpoint for the reply.

---

## BUG-124 — `isCritical` future-proofing is latent and untested
**Description**: A speculative `isCritical` classification path has no caller and no tests.
**Steps to reproduce**: N/A.
**Expected behavior**: Dead code removed or exercised.
**Actual behavior**: Latent, unexercised branch.
**Suspected cause**: Defensive coding added without a use case.
**Files involved**: `app-bridge/.../BridgeService.kt`
**Priority**: Very Low
**Status**: ⚠️ WONTFIX (Session 033) — latent future-proofing; left as-is.

---

## BUG-125 — LEFT down fires both tap() and a continuous stroke
**Description**: `AccessibilityCommandBus` issued `tap()` on LEFT down AND started a drag stroke on
subsequent moves, so a click or small drag double-fired (the down tap plus the gesture).
**Steps to reproduce**: Click with the bridge while in accessibility mode.
**Expected behavior**: A click dispatches exactly one tap; a drag dispatches only the gesture.
**Actual behavior**: Both a tap (on down) and a drag stroke (on move) were dispatched.
**Suspected cause**: Tap was performed eagerly on MouseButtonDown instead of deferred to mouse-up.
**Files involved**: `accessibility-receiver/.../AccessibilityCommandBus.kt`
**Priority**: Low
**Status**: ✅ FIXED (Session 034)
**Fix**: Tap is now deferred to MouseButtonUp; it fires only when the pointer moved less than
`CLICK_MOVE_THRESHOLD_PX` since down (a click), so a real drag no longer also taps.

---

## BUG-126 — Receiver falls back to open-input mode after DISCONNECT
**Description**: After a DISCONNECT the receiver accepted any LAN host's input (no `pairedBridgeIp`
enforcement when unpaired).
**Steps to reproduce**: DISCONNECT, then any host on the LAN sends input.
**Expected behavior**: Only the paired bridge may inject input; unknown senders are dropped.
**Actual behavior**: Open-mode injection accepted from any host.
**Suspected cause**: Validation rule only dropped unknown senders while `pairedBridgeIp` was non-empty.
**Files involved**: `app-receiver/.../ReceiverService.kt`
**Priority**: Medium
**Status**: ✅ FIXED (Session 034)
**Fix**: When a session PIN is configured (always, after first run) the receive loop drops every
non-PAIR_REQUEST packet whose sender is not the paired bridge, closing the open-input fallback.
Trade-off: a bridge with no PIN can no longer inject until its PIN matches the receiver.

---

## BUG-127 — TextInput fields truncate long strings
**Description**: Receiver text fields cap length by design (UI constraint).
**Steps to reproduce**: Enter a very long PIN/session value.
**Expected behavior**: Accept arbitrary length or show a clear limit.
**Actual behavior**: Truncated by design.
**Suspected cause**: Intentional UI limit.
**Files involved**: `app-receiver/.../ui/screens/*`
**Priority**: Very Low
**Status**: ⚠️ WONTFIX (Session 033) — by design; not changed.

---

## BUG-128 — Bridge never re-pairs after the PIN/target-IP changes at runtime
**Description**: `BridgeService` runs the pairing handshake exactly once, at `startPipeline()`.
A PIN (or target IP) entered or corrected in Settings *after* the service is already running is
persisted to `BridgePreferences` but never sent — the service keeps sending the stale PIN captured
at startup, so the receiver permanently rejects it with "PIN does not match receiver display".
**Steps to reproduce**: Start the bridge service (PIN empty or wrong), then enter the correct 6-digit
PIN in Settings → pairing never succeeds.
**Expected behavior**: Changing the PIN/target IP while the service runs immediately re-attempts pairing.
**Actual behavior**: Pairing stays failed until the service is manually restarted.
**Suspected cause**: No runtime trigger to re-run `doPairing()`; `onStartCommand` only handles `ACTION_STOP`.
**Files involved**: `app-bridge/.../bridge/service/BridgeService.kt`, `app-bridge/.../bridge/viewmodel/BridgeViewModel.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 035)
**Fix**: Added `ACTION_REPAIR`; `BridgeViewModel.setPairingPin` (on a full 6-digit PIN) and
`setTargetIp` send it to the running service, which calls `rePair()` — resets `pairResponseDeferred`
and re-runs `doPairing()` against the live transport without restarting the pipeline.

---

## BUG-129 — USB host permission requested only from background service; bridge never gets access to keyboard/mouse

**Description**: All USB-permission acquisition lives in `BridgeService` (a background
`foregroundService`). The service calls `UsbManager.requestPermission()` from a non-foreground
context. On Android 10 (API 29, Redmi 9 / MIUI) the system permission dialog is silently dropped
when no foreground `Activity` is present, so `hasPermission()` stays false and `openDevice()`
returns null — the app never obtains access to the connected USB keyboard/mouse receiver.
**Steps to reproduce**: Plug the Portronics Key2 Combo receiver into the Redmi 9 OTG while the
bridge service is the only running component (app not foregrounded). Observe "Cannot open USB
device" in logs / no input is bridged; `DiagnosticsManager.usbPermissionGranted` stays false.
**Expected behavior**: Plugging in the USB receiver should reliably grant USB host access (even
when requested from a background service) so input capture starts.
**Actual behavior**: Permission dialog does not appear; app never gets access to the device.
**Suspected cause**: Android 10/MIUI only reliably shows the USB-permission dialog from a
foreground `Activity`. Requesting it from the service is the fragile path — the same consideration
the `btmouse`/`jdx` USB-host projects solved by requesting permission from a foreground Activity.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/MainActivity.kt`,
`app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 036)
**Fix**: `MainActivity` now acts as the authoritative foreground permission requester: on USB
attach / `onResume` scan, if the app lacks permission it calls `UsbManager.requestPermission()`
from the foreground `Activity` (reliable dialog), and only starts `BridgeService` once the
`ACTION_USB_PERMISSION` broadcast reports `EXTRA_PERMISSION_GRANTED`. The service path remains as a
fallback for the boot/notification-started case.

---

## BUG-130 — Auto-discovery used only the first interface's broadcast address; IP never found

**Description**: `AutoDiscovery` broadcast the receiver presence to a single, first-found
non-loopback interface broadcast address (or 255.255.255.255 fallback). On a hotspot/client
setup the first interface is often the wrong subnet, so the bridge never received the
announcement and stayed stuck "Searching" — the receiver showed "Listening, not paired" forever.
**Steps to reproduce**: Two phones on the same Wi-Fi/hotspot; start receiver then bridge with no
manual IP. Bridge never discovers receiver.
**Expected behavior**: Bridge auto-discovers the receiver IP on the active subnet and connects.
**Actual behavior**: Discovery silently fails; nothing reaches the receiver.
**Suspected cause**: Single broadcast target; also auto-discovery only ran when targetIp was blank
and restarted the whole pipeline on discovery.
**Files involved**: `shared-core/.../discovery/AutoDiscovery.kt`, `app-bridge/.../bridge/service/BridgeService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 037)
**Fix**: `AutoDiscovery` now broadcasts to every up, non-loopback interface broadcast address plus
255.255.255.255. `BridgeService` runs discovery unconditionally and (re)connects to the discovered
peer without restarting the whole pipeline.

---

## BUG-131 — PIN pairing gate blocked all input; user wanted direct connect (no PIN)

**Description**: The receiver dropped every packet from a sender whose IP was not in its paired
allowlist, and the bridge required a matching PIN to pair. This friction meant "nothing works"
out of the box. User explicitly requested removing the pairing system for a direct, same-Wi-Fi
connection.
**Steps to reproduce**: Default install; receiver shows "Listening, not paired"; bridge never pairs.
**Expected behavior**: On the same network the two apps connect directly and input flows.
**Actual behavior**: Input dropped until a PIN was manually matched on both sides.
**Files involved**: `app-receiver/.../receiver/service/ReceiverService.kt`, `app-bridge/.../bridge/service/BridgeService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 037)
**Fix**: Removed the PIN allowlist gate. Receiver accepts input from any LAN sender and records the
peer IP. Bridge connects directly (PIN handshake is now best-effort/non-fatal). Notifications no
longer show a PIN.

---

## BUG-132 — Shizuku permission never requested; keyboard injection impossible

**Description**: The app only called `Shizuku.checkSelfPermission()` and never `Shizuku.requestPermission()`,
and the permissions screen never mentioned Shizuku. Because the permission was always denied, the
app fell back to the AccessibilityService path, which CANNOT inject real key events (it can only
manipulate a focused EditText). So keyboard input never worked.
**Steps to reproduce**: Install receiver; enable accessibility; type via bridge. No key arrives.
**Expected behavior**: With Shizuku installed + granted, real key events inject at 1–5ms.
**Actual behavior**: Keyboard dead; only mouse/trackpad gestures work (via dispatchGesture).
**Suspected cause**: Missing runtime permission request + missing UI to grant it. An
AccessibilityService fundamentally cannot inject system key events — only Shizuku/InputManager can.
**Files involved**: `accessibility-receiver/.../accessibility/ShizukuInputInjector.kt`, `app-receiver/.../receiver/ui/screens/ReceiverPermissionsScreen.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 037)
**Fix**: `ShizukuInputInjector` now registers a permission-result listener and exposes
`requestPermissionIfNeeded(activity)`; `ReceiverPermissionsScreen` shows Shizuku state and a
one-tap grant button, with clear instructions to install/start Shizuku first.



## BUG-133 — Auto-discovery unreliable: bridge only connected with manual IP + PIN

**Description**: After removing the manual IP/PIN UI (BUG-130/131) the apps still failed to
connect automatically. The original discovery relied solely on the receiver broadcasting
`INPUTBRIDGE_RECEIVER` and the bridge passively listening. On real Wi-Fi/hotspot stacks one
direction's broadcast packet is frequently dropped, so the bridge stayed stuck "Searching" and
only linked when the user typed the receiver IP and PIN by hand.
**Steps to reproduce**: Two devices on same Wi-Fi; start receiver then bridge; no manual IP/PIN
entered. Bridge never connects.
**Expected behavior**: Bridge auto-connects within a few seconds of both apps running, no UI entry.
**Actual behavior**: Connection never happens without manual IP + PIN.
**Suspected cause**: One-way broadcast discovery is fragile; dropped broadcast → no discovery.
**Files involved**: `shared-core/.../discovery/AutoDiscovery.kt`, `app-bridge/.../service/BridgeService.kt`, `app-receiver/.../service/ReceiverService.kt`, `app-bridge/.../ui/screens/SettingsScreen.kt`, `app-receiver/.../ui/screens/ConnectionScreen.kt`, `app-receiver/.../ui/screens/ReceiverDiagnosticsScreen.kt`
**Priority**: Critical
**Status**: ✅ FIXED (Session 038)
**Fix**: Discovery made bidirectional — bridge also broadcasts `INPUTBRIDGE_QUERY` and the receiver
listens for it and replies directly to the bridge's discovery port (54322); receiver still
periodically broadcasts its presence. UI no longer asks for IP or PIN: bridge Settings shows
auto-discovered status, receiver Connection screen shows auto-connect guidance.

## BUG-134 — No fallback when broadcast discovery is blocked; trackpad dead without IP

**Description**: Auto-discovery (BUG-133) is the only way to connect. On some Wi-Fi/router
setups broadcasts are dropped (client isolation), so the bridge never finds the receiver and
the connection never happens. Because the on-screen trackpad read `prefs.targetIp` once at
mount, it also stayed dead whenever discovery had not populated the IP.
**Steps to reproduce**: Put both devices on a network that blocks UDP broadcasts; start both
apps. Bridge stays "Searching"; trackpad shows "Connecting…" forever.
**Expected behavior**: App connects even when broadcasts are blocked (manual IP fallback), and
the trackpad links as soon as the receiver IP is known.
**Actual behavior**: No connection; trackpad cannot capture mouse/keyboard.
**Suspected cause**: Discovery is environment-dependent; no manual fallback; trackpad used a
stale IP snapshot.
**Files involved**: `app-bridge/.../ui/screens/SettingsScreen.kt`, `app-bridge/.../ui/screens/BridgeTrackpadScreen.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 039)
**Fix**: Re-added an OPTIONAL "Receiver IP (optional)" field in bridge Settings (blank = auto).
The on-screen trackpad now polls the live discovered IP and (re)connects when available.

## BUG-135 — Bluetooth HID never discoverable; host cannot pair/find the phone

**Description**: In BT HID mode the bridge registered the HID app but never entered discoverable
mode, so the tablet/PC could never see the phone to pair with it. Result: BT HID "doesn't work"
even though the device supports the HID Device profile.
**Steps to reproduce**: Enable BT HID transport on the Redmi 9; try to pair/connect from the OnePlus.
The phone never appears in the tablet's Bluetooth scan.
**Expected behavior**: The phone is advertised as a discoverable Bluetooth keyboard+mouse; the
host pairs once and receives input.
**Actual behavior**: Host never sees the phone; no connection.
**Suspected cause**: Missing `ACTION_REQUEST_DISCOVERABLE`. Confirmed by decompiling the reference
app "Bluetooth Keyboard Mouse v6.23.2" — it calls `REQUEST_DISCOVERABLE` (300s) immediately after
HID registration. This also overturns the earlier assumption that Redmi 9 lacks the HID Device
role (that app works on the same hardware).
**Files involved**: `transport-bluetooth-hid/.../bt/BluetoothHidTransport.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 039)
**Fix**: After `registerApp()` succeeds, `BluetoothHidTransport` now requests discoverable mode
(`ACTION_REQUEST_DISCOVERABLE`, 300s, from the foreground service via FLAG_ACTIVITY_NEW_TASK).

## BUG-136 — Cursor overlay does not extend into status bar / nav bar / display cutout

**Description**: The `CursorOverlayService` window uses `MATCH_PARENT` with `FLAG_LAYOUT_IN_SCREEN`
but lacks `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` and `FLAG_LAYOUT_NO_LIMITS`. On the OnePlus Pad Go
(center punch-hole camera) the window is inset away from the cutout and the system bars, so the cursor
can never travel into those regions — the overlay visually "does not cover the full screen".
**Steps to reproduce**: Enable overlay on a device with a punch-hole/status bar; move the mouse to the
top edge / camera hole. The cursor halts before reaching the very edge.
**Expected behavior**: The cursor window covers the entire physical display edge-to-edge, including
behind the status bar, nav bar, and the display cutout (matching a real OS pointer).
**Actual behavior**: Overlay is inset; cursor cannot reach the true screen edges.
**Suspected cause**: Missing `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` and
`FLAG_LAYOUT_NO_LIMITS` on the overlay `WindowManager.LayoutParams` (web-verified: Android avoids
cutouts by default; API 30+ uses `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` instead of the old negative-y
offset workaround).
**Files involved**: `app-receiver/.../receiver/service/CursorOverlayService.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 040)
**Fix**: Added `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + `FLAG_LAYOUT_NO_LIMITS` to the overlay
`WindowManager.LayoutParams`.

## BUG-137 — Cursor coordinate space defaults to 1080×2400 until a11y reports size; can't span full screen

**Description**: `AccessibilityCommandBus` clamps the virtual cursor to `screenWidth/screenHeight`, which
default to 1080×2400 and are only updated by `InputBridgeAccessibilityService.onServiceConnected()` via
`getRealScreenSize()` (which uses `currentWindowMetrics.bounds` — the *current* window bounds, NOT the
full physical screen). If accessibility is disabled, slow, or reports a bounds smaller than the real
screen, the cursor is confined to a sub-region and "cannot cover the full screen".
**Steps to reproduce**: Start overlay without (or before) the accessibility service connecting, or on a
tablet whose `currentWindowMetrics.bounds` excludes system bars; move the mouse far right/down. Cursor
stops at ~1080px / 2400px, not the real edge.
**Expected behavior**: Cursor coordinate space equals the true physical screen size (including system
bars/cutout) regardless of accessibility state.
**Actual behavior**: Cursor clamps to 1080×2400 (or the smaller a11y-reported bounds).
**Suspected cause**: (a) no independent real-screen-size source for the overlay; (b) accessibility uses
`currentWindowMetrics.bounds` instead of the full-screen `maximumWindowMetrics` (API 30+) / `getRealSize`.
**Files involved**: `app-receiver/.../receiver/service/CursorOverlayService.kt`,
`accessibility-receiver/.../accessibility/InputBridgeAccessibilityService.kt`,
`accessibility-receiver/.../accessibility/AccessibilityCommandBus.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 040)
**Fix**: Added `realScreenSize(context)` (ScreenMetrics.kt) using `maximumWindowMetrics` (API 30+) /
`getRealSize`; fed from `CursorOverlayService.onCreate` up-front and used by the accessibility service
on connect, so the cursor space equals the true physical screen regardless of a11y state.

## BUG-138 — BT HID descriptor/report not maximally host-compatible (no "any device" parity)

**Description**: Studied the decompiled reference app "Bluetooth Keyboard Mouse v6.23.2" (the ground truth
for a HID keyboard+mouse that works on any Bluetooth host). Its `ClassicHidService`/`z00`/`e10` use the
**canonical boot-protocol** HID layouts: keyboard usage max 0x65 (101 keys) + LED Output collection, mouse
with 5 buttons + AC Pan (horizontal scroll) on the Consumer page. Our `HidDescriptor`/`HidReportBuilder`
diverged: keyboard usage max was 0x91 (some hosts reject non-boot maxima), the mouse exposed only 3
buttons with no AC Pan, and Back/Forward mouse buttons were mapped to 0x00 (dropped). This reduces the
chance of working on strict/odd hosts and loses horizontal scroll + back/forward over BT HID.
**Steps to reproduce**: Register as BT HID combo device; connect to a strict host (some TVs/PCs/tablets).
Keyboard may be rejected for non-boot usage max; horizontal scroll and mouse back/forward produce nothing.
**Expected behavior**: BT HID works on any Bluetooth host exactly like the reference — boot-standard
keyboard, 5-button mouse with horizontal scroll, LED state supported.
**Actual behavior**: Keyboard usage max 0x91; mouse limited to 3 buttons, no AC Pan; back/forward dropped.
**Suspected cause**: Descriptor hand-written without matching the proven reference boot layouts.
**Files involved**: `transport-bluetooth-hid/.../bt/HidDescriptor.kt`,
`transport-bluetooth-hid/.../bt/HidReportBuilder.kt`,
`transport-bluetooth-hid/.../bt/BluetoothHidTransport.kt`
**Priority**: High
**Status**: ✅ FIXED (Session 041)
**Fix**: Keyboard usage max → 0x65 + added LED Output collection; mouse → 5 buttons + AC Pan (Consumer
0x0238) and a 5-byte report; `MouseButton.BACK/FORWARD` map to HID buttons 4/5; `Scroll` forwards `dx` as
AC Pan. Latency-sensitive UDP + Shizuku path intentionally left untouched (reference is slow by design).

## BUG-139 — Bridge UDP hot path adds a coroutine dispatch hop per mouse packet

**Description**: On the bridge, every `InputEvent` (including the 125 Hz mouse/scroll stream) is pushed
into `UdpTransport`'s `inputChannel` and later dequeued by the `select()` send loop. That is one extra
coroutine context switch + channel hand-off per packet on the highest-frequency path. `sendDirect()`
already existed for the trackpad fast path and sends inline on the caller thread (`sock.send()`), skipping
the channel entirely. The per-event `prefs.bridgeSensitivity` read is also a SharedPreferences-backed
property lookup repeated 125×/sec for no reason (sensitivity is fixed for the capture session).
**Steps to reproduce**: Build/run bridge; capture a USB mouse; profile `BridgeService.startCapture`
collector. Mouse/scroll packets route through `inputChannel` → `select()` loop instead of sending inline.
**Expected behavior**: The 125 Hz mouse/scroll deltas are sent inline on the collector thread with the
fewest possible hops; keys/clicks keep channel ordering guarantees.
**Actual behavior**: Mouse/scroll packets incur an extra dispatch hop + channel allocation per packet.
**Suspected cause**: `udpTransport.send()` used unconditionally for all events; sensitivity re-read each event.
**Files involved**: `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`
(`startCapture` collector), `transport-wifi/.../wifi/UdpTransport.kt` (`sendDirect`)
**Priority**: Medium
**Status**: ✅ FIXED (Session 042)
**Fix**: `MouseMove`/`Scroll` now call `udpTransport.sendDirect(packet)` (inline send, skips the channel
+ select dispatch); keys/clicks still use `udpTransport.send()` to preserve ordering. `bridgeSensitivity`
is cached once per capture session in a local `val` instead of being re-read per event.
