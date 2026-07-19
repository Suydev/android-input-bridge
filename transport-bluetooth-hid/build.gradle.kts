plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.transport.bt"
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    // Transport interface lives in transport-wifi for now;
    // will be moved to shared-core when BT HID is implemented (Phase 6)
    implementation(project(":transport-wifi"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
