plugins {
    `kotlin-dsl`
}

group = "com.inputbridge.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Use direct Maven coordinates — version catalog isn't accessible here without extra wiring
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
