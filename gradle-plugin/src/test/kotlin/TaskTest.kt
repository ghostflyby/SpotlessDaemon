/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */
import com.diffplug.gradle.spotless.SpotlessPlugin
import dev.ghostflyby.spotless.daemon.SpotlessDaemon
import dev.ghostflyby.spotless.daemon.SpotlessDaemonTask
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.named
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFails


class TaskTest {
    private fun project(): Project {
        val project = ProjectBuilder.builder().build()
        project.apply<SpotlessDaemon>()
        project.apply<SpotlessPlugin>()
        return project
    }

    private fun Project.spotlessDaemonTask() = tasks.named<SpotlessDaemonTask>(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME)


    /**
     * @see [SpotlessDaemonTask.port]
     * @see [SpotlessDaemonTask.unixSocket]
     */
    @Test
    fun `run without host config`() {
        val project = project()
        val task = project.spotlessDaemonTask()
        assertFails("Should fail when neither port nor unixSocket is set") {
            task.get().actions.forEach { it.execute(task.get()) }
        }
    }


}