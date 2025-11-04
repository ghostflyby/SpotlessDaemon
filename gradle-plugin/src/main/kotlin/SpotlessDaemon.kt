/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.Incremental
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject

@Suppress("unused")
class SpotlessDaemon : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin(SPOTLESS_PLUGIN_ID) {
            target.apply()
        }
    }

    companion object {
        const val SPOTLESS_PLUGIN_ID = "com.diffplug.spotless"
        const val SPOTLESS_DAEMON_TASK_NAME = "spotlessDaemon"
    }
}

private fun Project.apply() {
    val s = gradle.sharedServices.registerIfAbsent("SpotlessDaemonBridgeService", FutureService::class.java) {}

    tasks.register<SpotlessDaemonTask>(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME) {
        usesService(s)
        projectRoot.set(layout.projectDirectory)
        tasks.withType<SpotlessTask>().forEach {
            targets.from(it.target)
            val formatter =
                Formatter.builder().steps(it.stepsInternalRoundtrip.steps).lineEndingsPolicy(it.lineEndingsPolicy.get())
                    .encoding(Charset.forName(it.encoding)).build()
            formatterMapping.put(it.target, formatter)
        }
    }
}

@DisableCachingByDefault(because = "Daemon-like task; no reproducible outputs")
internal abstract class SpotlessDaemonTask @Inject constructor() : DefaultTask() {

    init {
        unixSocket.convention(project.providers.gradleProperty("spotlessDaemon.unixSocket"))
        port.convention(project.providers.gradleProperty("spotlessDaemon.port").map { it.toInt() })
    }

    @get:Input
    @get:Optional
    abstract val unixSocket: Property<String>

    @get:InputDirectory
    abstract val projectRoot: DirectoryProperty

    @get:Input
    abstract val port: Property<Int>

    @get:ServiceReference
    abstract val service: Property<FutureService>

    @get:InputFiles
    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val targets: ConfigurableFileCollection

    @get:Input
    abstract val formatterMapping: MapProperty<FileCollection, Formatter>

    @TaskAction
    fun run() {

        val server = embeddedServer(
            CIO,
            configure = {
                if (unixSocket.isPresent) unixConnector(unixSocket.get())
                else connector {
                    port = this@SpotlessDaemonTask.port.get()
                    host = "localhost"
                }
            },
        ) {
            install(IgnoreTrailingSlash)
        }

        val channel = Channel<Req>(Channel.UNLIMITED)
        server.application.routing {
            post("") {
                action(channel)
            }
            get("") {
                call.respondText("Spotless Daemon is running.")
            }
        }

        val param = service.get().parameters
        param.projectRoot.set(projectRoot)
        param.fileCollection.from(targets)
        param.formatterMapping.set(formatterMapping)




        logger.lifecycle("Spotless Daemon running on port ${port.get()}")


        runBlocking {
            try {
                server.startSuspend(wait = false)

                mainLoop(channel, service.get())

            } finally {
                channel.close()
                server.stopSuspend(1000, 2000)
            }
        }

    }

}

internal suspend fun mainLoop(channel: Channel<Req>, service: FutureService) {
    for ((path, content, future) in channel) {

//        val id = service.putFuture(future)

        val formatter = service.getFormatterFor(path) ?: future.run {
            complete(Resp.NotFormatted("File not covered by Spotless: $path", HttpStatusCode.NotFound))
            continue
        }

        val bytes = content.toByteArray(formatter.encoding)
        val state = DirtyState.of(formatter, File(path), bytes, content)


        if (state.isClean) {
            future.complete(Resp.NotFormatted("", HttpStatusCode.OK))
            continue
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

internal data class Req(val path: String, val content: String, val future: CompletableDeferred<Resp>)

internal suspend fun RoutingContext.action(channel: Channel<Req>) {
    val path = call.queryParameters["path"] ?: return call.respondText(
        "Missing path query parameter",
        status = HttpStatusCode.BadRequest,
    )
    val content = call.receiveText()

    val future = CompletableDeferred<Resp>()

    channel.send(Req(path, content, future))

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


