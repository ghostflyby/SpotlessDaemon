/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

package dev.ghostflyby.spotless.daemon

import com.diffplug.spotless.Formatter
import org.gradle.api.file.FileCollection
import java.io.File

internal data class FormatterEntry(
    val files: FileCollection,
    val formatter: Formatter,
    val projectDir: File,
)
