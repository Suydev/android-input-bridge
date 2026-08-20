plugins {
    id("inputbridge.android.app")
    id("inputbridge.android.compose")
}

android {
    namespace = "com.inputbridge"

    defaultConfig {
        applicationId = "com.inputbridge"
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // App modules (converted to libraries)
    implementation(project(":app-bridge"))
    implementation(project(":app-receiver"))

    // Core modules
    implementation(project(":shared-core"))
    implementation(project(":protocol"))
    implementation(project(":input-capture"))
    implementation(project(":transport-wifi"))
    implementation(project(":transport-bluetooth-hid"))
    implementation(project(":accessibility-receiver"))
    implementation(project(":diagnostics"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Material Design
    implementation(libs.material)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging
    implementation(libs.timber)

    // Dependency Injection
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // UI testing
    androidTestImplementation(libs.androidx.ui.test.manifest)
}