import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/**
 * Convention plugin that enables Jetpack Compose in a module.
 * Apply alongside androidApp or androidLibrary conventions.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val extension = extensions.getByType<CommonExtension<*, *, *, *, *, *>>()
            extension.buildFeatures {
                compose = true
            }
        }
    }
}
