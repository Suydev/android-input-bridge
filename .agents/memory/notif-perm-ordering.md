---
name: Notification permission ordering
description: ActivityResultLauncher.launch() must be called after setContent{} in ComponentActivity — OEM builds crash if called before the Compose LifecycleOwner is established
---

# Notification Permission Launch Ordering

**Rule:** Any `ActivityResultLauncher.launch()` call in `ComponentActivity.onCreate()` must be
placed **after** `setContent {}`, not before it.

**Why:** `setContent {}` establishes the Compose `LifecycleOwner` and wires it to the
`ActivityResultRegistry`. On stock Android, calling `launch()` before `setContent {}` works by
accident — the registry queues the dispatch and delivers it after the composition is ready. On
OEM builds (OnePlus OxygenOS, Xiaomi MIUI), the registry enforces strict ordering and throws
`IllegalStateException: LifecycleOwner not attached` when the permission result is dispatched
back to a LifecycleOwner that hasn't been created yet. This caused BUG-058: a first-launch crash
on the OnePlus Pad Go (OxygenOS, API 33) immediately after the POST_NOTIFICATIONS dialog appeared.

**How to apply:** In any `ComponentActivity.onCreate()` that calls `launch()`:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // ... any sync setup (applyKeepScreenOn, etc.) ...
    setContent { /* Compose tree — this establishes the LifecycleOwner */ }
    // ONLY after setContent{} is it safe to call launch():
    requestNotificationPermissionIfNeeded()
}
```
Affects: `app-receiver/.../MainActivity.kt` and `app-bridge/.../MainActivity.kt` (fixed in Session 016).
