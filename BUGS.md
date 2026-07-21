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
