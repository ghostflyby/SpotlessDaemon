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

    private val log = Logging.getLogger(FormatAction::class.java)

    override fun execute() {

        val path = parameters.path.get()

        val service = parameters.fileService.get()

        val reply = service.getReplyFuture(parameters.reply.get())


        val formatter = service.getFormatterFor(path) ?: return reply.run {
            log.warn("File not covered by Spotless: $path")
            complete(Resp.NotFormatted("File not covered by Spotless: $path", HttpStatusCode.NotFound))
        }

        val content = parameters.content.get()
        val bytes = content.toByteArray(formatter.encoding)
        val state = DirtyState.of(formatter, File(path), bytes, content)


        if (state.isClean) {
            log.debug("File already clean: $path")
            reply.complete(Resp.NotFormatted("", HttpStatusCode.OK))
            return
        }



        log.info("Formatted $path")
        reply.complete(Resp.Formatted(state, formatter.encoding))
    }
}
