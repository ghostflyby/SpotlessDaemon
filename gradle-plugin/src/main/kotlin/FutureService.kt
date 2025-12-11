/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import com.diffplug.spotless.Formatter
import kotlinx.coroutines.CompletableDeferred
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

internal abstract class FutureService @Inject constructor() : BuildService<FutureService.FutureServiceParams> {

    private val log = Logging.getLogger(FutureService::class.java)

    internal interface FutureServiceParams : BuildServiceParameters {
        val fileCollection: ConfigurableFileCollection
        val formatterMapping: ListProperty<Pair<FileCollection, Formatter>>
        val projectRoot: DirectoryProperty
    }

    fun getFormatterFor(file: String): Formatter? {
        val relativeFile = parameters.projectRoot.get().file(file).asFile
        val targets = parameters.fileCollection
        log.info("all known files: ${targets.files.joinToString("\n")}")
        if (targets.contains(relativeFile)) {
            return getFormatterFor(relativeFile)
        }
        val absFile = File(file)
        if (targets.contains(absFile)) {
            return getFormatterFor(absFile)
        }
        return getFormatterFor(relativeFile) ?: getFormatterFor(File(file))
    }

    private val map = ConcurrentHashMap<UUID, CompletableDeferred<Resp>>()

    private fun getFormatterFor(file: File): Formatter? {


        val mapping = parameters.formatterMapping.get()
        for ((key, value) in mapping) {
            if (key.contains(file)) {
                log.info("Resolved formatter for ${file.absolutePath}")
                return value
            }
        }
        log.info("No formatter mapping found for ${file.absolutePath}")
        return null
    }

    fun getReplyFuture(id: UUID): CompletableDeferred<Resp> {
        return map.remove(id) ?: CompletableDeferred()
    }

    fun putFuture(future: CompletableDeferred<Resp>): UUID {
        val id = UUID.randomUUID()
        map[id] = future
        return id
    }
}
