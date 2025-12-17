/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import com.diffplug.spotless.DirtyState
import io.ktor.http.*
import org.gradle.api.logging.Logging
import org.gradle.workers.WorkAction
import java.io.File
import javax.inject.Inject

internal abstract class FormatAction @Inject constructor() : WorkAction<FormatParams> {

    private val logger = Logging.getLogger(FormatAction::class.java)

    override fun execute() {

        val path = parameters.path.get()

        val service = parameters.fileService.get()

        val future = service.getReplyFuture(parameters.reply.get())

        val content = parameters.content.get()
        val dryrun = parameters.dryrun.get()

        val formatter = service.getFormatterFor(path) ?: future.run {
            logger.info("File not covered by Spotless: $path")
            complete(Resp.NotFormatted("File not covered by Spotless: $path", HttpStatusCode.NotFound))
            return
        }

        if (dryrun) {
            logger.info("Dry run request succeeded for $path")
            future.complete(Resp.NotFormatted("", HttpStatusCode.OK))
            return
        }

        val bytes = content.toByteArray(formatter.encoding)
        val state = DirtyState.of(formatter, File(path), bytes, content)


        if (state.isClean) {
            logger.info("File already clean: $path")
            future.complete(Resp.NotFormatted("", HttpStatusCode.OK))
            return
        }


        if (state.didNotConverge()) {
            logger.info("Formatter did not converge for $path")
        } else {
            logger.info("Formatted $path")
        }

        future.complete(Resp.Formatted(state, formatter.encoding))
    }
}
