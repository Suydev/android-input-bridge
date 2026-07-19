plugins {
    id("inputbridge.android.library")
}

android {
    namespace = "com.inputbridge.diagnostics"
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
