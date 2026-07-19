import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin that enables Jetpack Compose in a module.
 * Apply alongside androidApp or androidLibrary conventions.
 *
 * Uses pluginManager.withPlugin() to detect which Android plugin is present
 * before configuring, because getByType<CommonExtension> fails in AGP 8.x —
 * the concrete type registered is BaseAppModuleExtension, not CommonExtension.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<AppExtension> {
                    buildFeatures.compose = true
                }
            }

            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> {
                    buildFeatures.compose = true
                }
            }
        }
    }
}
