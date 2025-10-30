/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters
import java.util.*

internal interface FormatParams : WorkParameters {
    val path: Property<String>
    val content: Property<String>
    val reply: Property<UUID>
    val fileService: Property<FutureService>
}