plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.transport.bt"
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
