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

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
