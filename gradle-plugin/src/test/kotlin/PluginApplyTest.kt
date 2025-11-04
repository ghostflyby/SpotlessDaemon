/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

import com.diffplug.gradle.spotless.SpotlessPlugin
import dev.ghostflyby.spotless.daemon.SpotlessDaemon
import dev.ghostflyby.spotless.daemon.SpotlessDaemonTask
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.withType
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals


class PluginApplyTest {
    @Test
    fun `apply without spotless`() {
        val project = ProjectBuilder.builder().build()
        project.apply<SpotlessDaemon>()
        val size = project.tasks.withType<SpotlessDaemonTask>().toList().size
        assertEquals(0, size, "Should be no-op when Spotless plugin is not applied")
    }

    @Test
    fun `apply after spotless`() {
        val project = ProjectBuilder.builder().build()
        project.apply<SpotlessPlugin>()
        project.apply<SpotlessDaemon>()
        val size = project.tasks.withType<SpotlessDaemonTask>().toList().size
        assertNotEquals(0, size, "Should register tasks when Spotless plugin is applied first")
    }

    @Test
    fun `apply before spotless`() {
        val project = ProjectBuilder.builder().build()
        project.apply<SpotlessDaemon>()
        project.apply<SpotlessPlugin>()
        val size = project.tasks.withType<SpotlessDaemonTask>().toList().size
        assertNotEquals(0, size, "Should register tasks when Spotless plugin is applied after")
    }

}