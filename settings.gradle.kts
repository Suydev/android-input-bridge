pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-input-bridge"

include(":app-bridge")
include(":app-receiver")
include(":shared-core")
include(":protocol")
include(":input-capture")
include(":transport-wifi")
include(":transport-bluetooth-hid")
include(":accessibility-receiver")
include(":diagnostics")
