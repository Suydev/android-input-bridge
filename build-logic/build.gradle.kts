plugins {
    `kotlin-dsl`
}

group = "com.inputbridge.buildlogic"

// Align compileJava and compileKotlin to the same JVM target.
// With JDK 21 on the runner, kotlin-dsl would otherwise infer target=21
// while the explicit java block below set target=17 → InvalidUserCodeException.
kotlin {
    jvmToolchain(17)
}

// Use direct Maven coordinates — version catalog is wired via
// build-logic/settings.gradle.kts but the libs accessor is unavailable
// here in the included build's own build script without extra plumbing.
dependencies {
    compileOnly("com.android.tools.build:gradle:8.4.2")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    compileOnly("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.0")
}

gradlePlugin {
    plugins {
        register("androidApp") {
            id = "inputbridge.android.app"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("androidLibrary") {
            id = "inputbridge.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "inputbridge.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
