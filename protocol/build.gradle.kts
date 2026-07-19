plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.protocol"
}

dependencies {
    implementation(project(":shared-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
