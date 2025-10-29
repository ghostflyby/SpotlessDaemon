package dev.ghostflyby.spotless.daemon

import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused")
class SpotlessDaemon : Plugin<Project> {
    override fun apply(target: Project) {
        if (!target.plugins.hasPlugin(SPOTLESS_PLUGIN_ID)) {
            return
        }
        target.pluginManager.withPlugin(SPOTLESS_PLUGIN_ID) {
            target.apply()
        }
    }

    companion object {
        const val SPOTLESS_PLUGIN_ID = "com.diffplug.spotless"
    }
}

private fun Project.apply() {
}
