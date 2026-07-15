/*
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

import com.diffplug.spotless.Formatter
import com.diffplug.spotless.LineEnding
import com.diffplug.spotless.generic.EndWithNewlineStep
import com.diffplug.spotless.generic.TrimTrailingWhitespaceStep
import dev.ghostflyby.spotless.daemon.SkippedFormatterCache
import dev.ghostflyby.spotless.daemon.withSkippedSteps
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class FormatterOptionsTest {
    @Test
    fun `skipping steps removes every matching name and preserves formatter policy`() {
        val duplicate = TrimTrailingWhitespaceStep.create()
        val formatter = Formatter.builder()
            .steps(listOf(duplicate, duplicate, EndWithNewlineStep.create()))
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .encoding(StandardCharsets.UTF_16)
            .build()

        val filtered = formatter.withSkippedSteps(setOf("trimTrailingWhitespace"))

        assertEquals(listOf("endWithNewline"), filtered.steps.map { it.name })
        assertSame(formatter.lineEndingsPolicy, filtered.lineEndingsPolicy)
        assertEquals(formatter.encoding, filtered.encoding)

        val empty = formatter.withSkippedSteps(setOf("trimTrailingWhitespace", "endWithNewline"))
        assertTrue(empty.steps.isEmpty())
        assertSame(formatter.lineEndingsPolicy, empty.lineEndingsPolicy)
        assertEquals(formatter.encoding, empty.encoding)
    }

    @Test
    fun `unmatched step names preserve the original formatter`() {
        val formatter = Formatter.builder()
            .steps(listOf(TrimTrailingWhitespaceStep.create(), EndWithNewlineStep.create()))
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .encoding(StandardCharsets.UTF_8)
            .build()

        assertSame(formatter, formatter.withSkippedSteps(emptySet()))
        assertSame(formatter, formatter.withSkippedSteps(setOf("", "missing", "EndWithNewline")))
    }

    @Test
    fun `cache reuses effective skip combinations by formatter identity`(): Unit = runBlocking {
        val steps = listOf(TrimTrailingWhitespaceStep.create(), EndWithNewlineStep.create())
        val formatter = Formatter.builder()
            .steps(steps)
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .encoding(StandardCharsets.UTF_8)
            .build()
        val equalButDistinctFormatter = Formatter.builder()
            .steps(steps)
            .lineEndingsPolicy(formatter.lineEndingsPolicy)
            .encoding(formatter.encoding)
            .build()
        val cache = SkippedFormatterCache()

        val first = cache.get(formatter, setOf("trimTrailingWhitespace", "missing"))
        val sameCombination = cache.get(formatter, setOf("trimTrailingWhitespace"))
        val otherFormatter = cache.get(equalButDistinctFormatter, setOf("trimTrailingWhitespace"))

        assertSame(first, sameCombination)
        assertNotSame(first, otherFormatter)
        assertSame(formatter, cache.get(formatter, setOf("missing", "EndWithNewline")))
    }
}
