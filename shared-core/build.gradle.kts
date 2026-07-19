plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.core"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
