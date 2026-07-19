# InputBridge — Receiver App ProGuard Rules

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keepnames class org.koin.** { *; }
-dontwarn org.jetbrains.annotations.**

# Keep accessibility service (must not be renamed)
-keep class com.inputbridge.accessibility.InputBridgeAccessibilityService { *; }

-keep class com.inputbridge.** { *; }
