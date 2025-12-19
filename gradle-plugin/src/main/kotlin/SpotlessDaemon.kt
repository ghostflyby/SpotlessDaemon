/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import com.diffplug.gradle.spotless.SpotlessTask
import com.diffplug.spotless.Formatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.Charset
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@Suppress("unused")
class SpotlessDaemon : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin(SPOTLESS_PLUGIN_ID) {
            if (target.rootProject != target) {
                return@withPlugin
            }

            target.tasks.register<SpotlessDaemonTask>(SPOTLESS_DAEMON_TASK_NAME)

            target.afterEvaluate {
                target.configureRootTask()
            }
        }
    }

    companion object {
        const val SPOTLESS_PLUGIN_ID = "com.diffplug.spotless"
        const val SPOTLESS_DAEMON_TASK_NAME = "spotlessDaemon"
    }
}

private fun Project.configureRootTask() = afterEvaluate {

    val daemonTask = tasks.named<SpotlessDaemonTask>(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME)

    rootProject.allprojects {
        tasks.withType<SpotlessTask>().forEach {
            daemonTask.configure {
                targets.from(it.target)
                val formatter =
                    Formatter.builder().steps(it.stepsInternalRoundtrip.steps)
                        .lineEndingsPolicy(it.lineEndingsPolicy.get())
                        .encoding(Charset.forName(it.encoding)).build()
                formatterMapping.add(it.target to formatter)
            }
        }
    }

}

@DisableCachingByDefault(because = "Daemon-like task; no reproducible outputs")
internal abstract class SpotlessDaemonTask @Inject constructor(private val layout: ProjectLayout) : DefaultTask() {

    init {
        unixsocket.convention(project.providers.gradleProperty("dev.ghostflyby.spotless.daemon.unixsocket"))
        port.convention(project.providers.gradleProperty("dev.ghostflyby.spotless.daemon.port").map { it.toInt() })
    }

    @get:Input
    @get:Optional
    abstract val unixsocket: Property<String>

    @get:Input
    @get:Optional
    abstract val port: Property<Int>

    @get:Internal
    abstract val formatterMapping: ListProperty<Pair<FileCollection, Formatter>>

    @get:InputFiles
    abstract val targets: ConfigurableFileCollection

    @TaskAction
    fun run() {

        val listenDescription = when {
            unixsocket.isPresent -> "unix socket ${unixsocket.get()}"
            else -> "port ${port.get()}"
        }

        logger.lifecycle("Starting Spotless Daemon on $listenDescription with ${targets.files.size} targets")

        try {
            logger.info("Spotless Daemon started; awaiting formatting requests")
            val dispatcher = TaskMainDispatcher()
            KtorHttpAction(
                port = port,
                unixsocket = unixsocket,
                formatterMapping = formatterMapping,
                targets = targets,
                projectRoot = layout.projectDirectory,
                taskDispatcher = dispatcher,
            ).execute()
            dispatcher.mainLoop()
        } catch (e: Exception) {
            logger.error("Spotless Daemon encountered an error", e)
        } finally {
            logger.lifecycle("Spotless Daemon stopped")
        }

    }

}


internal class TaskMainDispatcher : CoroutineDispatcher() {
    private val queue = ArrayBlockingQueue<Runnable>(40)

    private var stopped = false
    private var thread: Thread? = null

    fun stop() {
        stopped = true
        thread?.interrupt()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    fun mainLoop() {
        thread = Thread.currentThread()
        while (!stopped) {
            val block = queue.take()
            block.run()
        }
    }
}