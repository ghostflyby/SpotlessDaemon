/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import com.diffplug.spotless.DirtyState
import io.ktor.http.*
import io.ktor.utils.io.*
import org.gradle.api.logging.Logging
import org.gradle.workers.WorkAction
import java.io.File
import javax.inject.Inject

internal abstract class FormatAction @Inject constructor() : WorkAction<FormatParams> {

    private val logger = Logging.getLogger(FormatAction::class.java)

    private val future get() = parameters.fileService.get().getReplyFuture(parameters.reply.get())

    private fun run(): Resp {
        val path = parameters.path.get()

        val service = parameters.fileService.get()


        val content = parameters.content.get()
        val dryrun = parameters.dryrun.get()

        val formatter = service.getFormatterFor(path) ?: run {
            logger.info("File not covered by Spotless: $path")
            return Resp.NotFormatted("File not covered by Spotless: $path", HttpStatusCode.NotFound)
        }

        if (dryrun) {
            logger.info("Dry run request succeeded for $path")
            return Resp.NotFormatted("", HttpStatusCode.OK)
        }

        val bytes = content.toByteArray(formatter.encoding)
        val state = DirtyState.of(formatter, File(path), bytes, content)


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

    override fun execute() {
        try {
            future.complete(run())
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                logger.error("Error formatting file ${parameters.path.get()}: ${t.message}", t)
                future.complete(
                    Resp.NotFormatted(
                        "Error formatting file: ${t.message}",
                        HttpStatusCode.InternalServerError,
                    ),
                )
            }
        }
    }
}
