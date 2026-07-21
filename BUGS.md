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
