# InputBridge — Bridge App ProGuard Rules

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Koin
-keepnames class org.koin.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# InputBridge models (safe to keep for debugging)
-keep class com.inputbridge.** { *; }
