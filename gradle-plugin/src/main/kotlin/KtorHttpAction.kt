/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import java.io.File
import java.nio.charset.Charset
import java.util.*

internal class KtorHttpAction(
    val port: Property<Int>,
    val unixsocket: Property<String>,
    val formatterMapping: List<FormatterEntry>,
    val targets: ConfigurableFileCollection,
    val projectRoot: Directory,
    val taskDispatcher: TaskMainDispatcher,
) {

    private val logger = Logging.getLogger(KtorHttpAction::class.java)

    fun execute() {

        val server = embeddedServer(
            CIO,
            configure = {
                if (unixsocket.isPresent) unixConnector(unixsocket.get()) {
                }
                else connector {
                    port = this@KtorHttpAction.port.get()
                    host = "127.0.0.1"
                }
            },
        ) {
            install(IgnoreTrailingSlash)
        }

        server.application.routing {
            post("") {
                action()
            }
            post("/stop") {
                logger.lifecycle("Stop requested; shutting down Spotless Daemon")
                call.respond(HttpStatusCode.OK)
                server.stop(1000, 2000)
                taskDispatcher.stop()
            }
            get("") {
                call.respondText("Spotless Daemon is running.")
            }
            get("/encoding") {
                val path = call.queryParameters["path"] ?: return@get call.respondText(
                    "Missing path query parameter",
                    status = HttpStatusCode.BadRequest,
                )

                val formatter = getFormatterFor(path)

                if (formatter == null) {
                    call.respondText("File not covered by Spotless: $path", status = HttpStatusCode.NotFound)
                    return@get
                }

                call.respondText(formatter.encoding.name(), ContentType.Text.Plain.withCharset(formatter.encoding))
            }
        }

        server.start(wait = false)
    }

    fun getFormatterFor(file: String): Formatter? {
        val relativeFile = projectRoot.file(file).asFile
        logger.info("all known files: ${targets.files.joinToString("\n")}")
        if (targets.contains(relativeFile)) {
            return getFormatterFor(relativeFile)
        }
        val absFile = File(file)
        if (targets.contains(absFile)) {
            return getFormatterFor(absFile)
        }

        val realPath = try {
            relativeFile.toPath().toRealPath().toFile()
        } catch (_: java.nio.file.NoSuchFileException) {
            logger.info("File does not exist for real path resolution: ${relativeFile.path}")
            return null
        }

        if (targets.contains(realPath)) {
            return getFormatterFor(realPath)
        }
        return null
    }

    private fun getFormatterFor(file: File): Formatter? {
        for (entry in formatterMapping) {
            if (entry.files.contains(file)) {
                logger.info("Resolved formatter for ${file.absolutePath} from project ${entry.projectPath}")
                return entry.formatter
            }
        }
        logger.info("No formatter mapping found for ${file.absolutePath}")
        return null
    }

    internal suspend fun RoutingContext.action() {
        val path = call.queryParameters["path"] ?: return call.respondText(
            "Missing path query parameter",
            status = HttpStatusCode.BadRequest,
        )
        val dryrun = call.queryParameters["dryrun"] != null
        val content = call.receiveText()

        logger.info("Handling request path=$path dryrun=$dryrun")

        val result =
            run(path, dryrun, content)

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


    private val lock = Mutex()
    private val gates = IdentityHashMap<Any, CompletableDeferred<Unit>>() // key 按引用(identity)比较

    suspend fun <R> firstTimeLatch(
        key: Any,
        first: suspend () -> R,
        later: suspend () -> R,
    ): R {
        val gate: CompletableDeferred<Unit>
        val isFirst: Boolean

        lock.withLock {
            val existing = gates[key]
            if (existing == null) {
                gate = CompletableDeferred()
                gates[key] = gate
                isFirst = true
            } else {
                gate = existing
                isFirst = false
            }
        }

        return if (isFirst) {
            try {
                val r = first()
                gate.complete(Unit)
                r
            } catch (t: Throwable) {
                gate.completeExceptionally(t)
                throw t
            }
        } else {
            gate.await()
            later()
        }
    }

    suspend fun run(path: String, dryrun: Boolean, content: String): Resp {
        val formatter = getFormatterFor(path) ?: run {
            logger.info("File not covered by Spotless: $path")
            return Resp.NotFormatted("File not covered by Spotless: $path", HttpStatusCode.NotFound)
        }

        if (dryrun) {
            logger.info("Dry run request succeeded for $path")
            return Resp.NotFormatted("", HttpStatusCode.OK)
        }

        val bytes = content.toByteArray(formatter.encoding)
        val state: DirtyState =
            firstTimeLatch(
                formatter,
                first = {
                    logger.info("Initializing formatter for first time for $path")
                    withContext(taskDispatcher) {
                        DirtyState.of(formatter, File(path), bytes, content)
                    }
                },
                later = {
                    logger.info("Formatter already initialized; proceeding for $path")
                    withContext(Dispatchers.IO) {
                        DirtyState.of(formatter, File(path), bytes, content)
                    }
                },
            )


        if (state.isClean) {
            logger.info("File already clean: $path")
            return Resp.NotFormatted("", HttpStatusCode.OK)
        }


        if (state.didNotConverge()) {
            logger.info("Formatter did not converge for $path")
        } else {
            logger.info("Formatted $path")
        }

        return Resp.Formatted(state, formatter.encoding)
    }


}

internal sealed interface Resp {
    data class NotFormatted(val content: String, val status: HttpStatusCode) : Resp
    data class Formatted(val state: DirtyState, val charset: Charset) : Resp
}
