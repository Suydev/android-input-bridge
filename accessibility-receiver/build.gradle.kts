plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.accessibility"
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    implementation(project(":diagnostics"))  // DiagnosticsManager used in InputBridgeAccessibilityService
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // Shizuku: privileged input injection via InputManager (1-5ms vs 10-30ms dispatchGesture)
    implementation(libs.shizuku.api)
    compileOnly(libs.shizuku.processor)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
