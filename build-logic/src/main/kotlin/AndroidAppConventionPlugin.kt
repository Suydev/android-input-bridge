import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin for Android application modules.
 * Applies standard configuration shared across app-bridge and app-receiver.
 */
class AndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 29
                    targetSdk = 35
                    versionCode = 1
                    versionName = "0.1.0"
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                buildFeatures {
                    buildConfig = true
                }

                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
                }

            buildTypes {
                release {
                    isMinifyEnabled = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro"
                    )
                    // Signing configuration is provided via environment variables set by CI
                    val storePath = System.getenv("SIGNING_KEYSTORE_PATH")
                    val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    if (storePath != null && keyAlias != null && keyPassword != null && storePassword != null) {
                        signingConfig {
                            storeFile = file(storePath)
                            storePassword = storePassword
                            keyAlias = keyAlias
                            keyPassword = keyPassword
                        }
                    }
                }
                debug {
                    isDebuggable = true
                    applicationIdSuffix = ".debug"
                }
            }
            }

            // Align Kotlin JVM target with Java target
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }
}
