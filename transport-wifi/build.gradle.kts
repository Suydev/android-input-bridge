plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.transport.wifi"
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.network)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
