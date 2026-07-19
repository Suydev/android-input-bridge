plugins {
    id("inputbridge.android.app")
    id("inputbridge.android.compose")
}

android {
    namespace = "com.inputbridge.bridge"

    defaultConfig {
        applicationId = "com.inputbridge.bridge"
    }
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    implementation(project(":input-capture"))
    implementation(project(":transport-wifi"))
    implementation(project(":transport-bluetooth-hid"))
    implementation(project(":diagnostics"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.timber)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
