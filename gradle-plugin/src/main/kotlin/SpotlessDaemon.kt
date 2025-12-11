/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import com.diffplug.gradle.spotless.SpotlessPlugin
import com.diffplug.gradle.spotless.SpotlessTask
import com.diffplug.spotless.DirtyState
import com.diffplug.spotless.Formatter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject

@Suppress("unused")
class SpotlessDaemon : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin(SPOTLESS_PLUGIN_ID) {
            if (target.rootProject != target) {
                return@withPlugin
            }
            target.configureRootTask()
        }
    }

    companion object {
        const val SPOTLESS_PLUGIN_ID = "com.diffplug.spotless"
        const val SPOTLESS_DAEMON_TASK_NAME = "spotlessDaemon"
    }
}

private fun Project.configureRootTask() {

    val serviceProvider =
        gradle.sharedServices.registerIfAbsent("SpotlessDaemonBridgeService", FutureService::class.java) {}

    val daemonTask = tasks.register<SpotlessDaemonTask>(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME) {
        usesService(serviceProvider)
    }

    gradle.allprojects {
        val project = this
        plugins.withType<SpotlessPlugin>().configureEach {

            project.tasks.withType<SpotlessTask>().configureEach {
                daemonTask.configure {
                    targets.from(target)
                    val formatter =
                        Formatter.builder().steps(stepsInternalRoundtrip.steps)
                            .lineEndingsPolicy(lineEndingsPolicy.get())
                            .encoding(Charset.forName(encoding)).build()
                    formatterMapping.add(target to formatter)
                }
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

    @get:ServiceReference
    abstract val service: Property<FutureService>

    @get:Input
    abstract val formatterMapping: ListProperty<Pair<FileCollection, Formatter>>

    @get:InputFiles
    abstract val targets: ConfigurableFileCollection

    @TaskAction
    fun run() {

        val listenDescription = when {
            unixsocket.isPresent -> "unix socket ${unixsocket.get()}"
            port.isPresent -> "port ${port.get()}"
            else -> "unknown address"
        }

        logger.lifecycle("Starting Spotless Daemon on $listenDescription with ${targets.files.size} targets")

        val server = embeddedServer(
            CIO,
            configure = {
                if (unixsocket.isPresent) unixConnector(unixsocket.get()) {
                }
                else connector {
                    port = this@SpotlessDaemonTask.port.get()
                    host = "127.0.0.1"
                }
            },
        ) {
            install(IgnoreTrailingSlash)
        }

        val channel = Channel<Req>(Channel.UNLIMITED)
        server.application.routing {
            post("") {
                action(channel, logger)
            }
            post("/stop") {
                logger.lifecycle("Stop requested; shutting down Spotless Daemon")
                call.respond(HttpStatusCode.OK)
                channel.cancel()
            }
            get("") {
                call.respondText("Spotless Daemon is running.")
            }
        }

        val param = service.get().parameters
        param.projectRoot.set(layout.projectDirectory)
        param.fileCollection.from(targets)
        param.formatterMapping.set(formatterMapping)

        runBlocking {
            try {
                server.startSuspend(wait = false)

                logger.info("Spotless Daemon started; awaiting formatting requests")

                mainLoop(channel, service.get(), logger)

            } catch (_: CancellationException) {
            } catch (e: Exception) {
                logger.error("Spotless Daemon encountered an error", e)
            } finally {
                channel.close()
                server.stopSuspend(1000, 2000)
                logger.lifecycle("Spotless Daemon stopped")
            }
        }

    }

}

internal suspend fun mainLoop(channel: Channel<Req>, service: FutureService, logger: Logger) {
    for ((path, content, future, dryrun) in channel) {

        logger.info("Received request for $path (dryrun=$dryrun)")

//        val id = service.putFuture(future)

        val formatter = service.getFormatterFor(path) ?: future.run {
            logger.info("File not covered by Spotless: $path")
            complete(Resp.NotFormatted("File not covered by Spotless: $path", HttpStatusCode.NotFound))
            continue
        }

        if (dryrun) {
            logger.info("Dry run request succeeded for $path")
            future.complete(Resp.NotFormatted("", HttpStatusCode.OK))
            continue
        }

        val bytes = content.toByteArray(formatter.encoding)
        val state = DirtyState.of(formatter, File(path), bytes, content)


        if (state.isClean) {
            logger.info("File already clean: $path")
            future.complete(Resp.NotFormatted("", HttpStatusCode.OK))
            continue
        }


        if (state.didNotConverge()) {
            logger.info("Formatter did not converge for $path")
        } else {
            logger.info("Formatted $path")
        }

        future.complete(Resp.Formatted(state, formatter.encoding))

//                worker.noIsolation().submit(FormatAction::class.java) {
//                    this.path.set(path)
//                    this.content.set(content)
//                    fileService.set(service)
//                    reply.set(id)
//                }
    }
}

internal data class Req(
    val path: String,
    val content: String,
    val future: CompletableDeferred<Resp>,
    val dryrun: Boolean,
)

internal suspend fun RoutingContext.action(channel: Channel<Req>, logger: Logger) {
    val path = call.queryParameters["path"] ?: return call.respondText(
        "Missing path query parameter",
        status = HttpStatusCode.BadRequest,
    )
    val dryrun = call.queryParameters["dryrun"] != null
    val content = call.receiveText()

    logger.info("Handling request path=$path dryrun=$dryrun")

    val future = CompletableDeferred<Resp>()

    channel.send(Req(path, content, future, dryrun))

    val result = future.await()

    if (result is Resp.NotFormatted) {
        call.respondText(result.content, status = result.status)
        return
    }

    val (state, charset) = (result as Resp.Formatted)


    val code = if (state.didNotConverge()) {
        HttpStatusCode.InternalServerError
    } else {
        HttpStatusCode.OK
    }
    call.respondOutputStream(contentType = ContentType.Text.Plain.withCharset(charset), status = code) {
        state.writeCanonicalTo(this)
    }

}

internal sealed interface Resp {
    data class NotFormatted(val content: String, val status: HttpStatusCode) : Resp
    data class Formatted(val state: DirtyState, val charset: Charset) : Resp
}
