/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

import dev.ghostflyby.spotless.daemon.SpotlessDaemon
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.div

class SubprojectFormatterPriorityTest(@param:TempDir val projectDir: Path) {

    private val port by lazy { findFreePort() }

    private val http by lazy {
        HttpClient(CIO) {
            defaultRequest {
                url {
                    host = "127.0.0.1"
                    protocol = URLProtocol.HTTP
                    port = this@SubprojectFormatterPriorityTest.port
                }
            }
        }
    }

    init {
        Files.createDirectories(projectDir)
        Files.writeString(
            projectDir / "settings.gradle.kts",
            """rootProject.name = "root"
include("child")
""".trimIndent(),
        )

        Files.writeString(
            projectDir / "build.gradle.kts",
            """
            plugins {
                id("com.diffplug.spotless")
                id("dev.ghostflyby.spotless.daemon")
            }

            repositories {
                mavenCentral()
            }

            spotless {
                format("rootTxt") {
                    target("**/*.txt")
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }

            subprojects {
                apply(plugin = "com.diffplug.spotless")
                repositories { mavenCentral() }
            }
            """.trimIndent(),
        )

        Files.createDirectories(projectDir / "child")
        Files.writeString(
            projectDir / "child" / "build.gradle.kts",
            """
            plugins {
                id("com.diffplug.spotless")
            }

            spotless {
                format("childTxt") {
                    target("*.txt")
                    trimTrailingWhitespace()
                }
            }
            """.trimIndent(),
        )
    }

    private fun startRunner() = thread(start = true) {
        try {
            GradleRunner.create().withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .forwardOutput()
                .withArguments(
                    SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME,
                    "--info",
                    "--stacktrace",
                    "-Pdev.ghostflyby.spotless.daemon.port=$port",
                ).build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startDaemonAndAwait(): Thread {
        val t = startRunner()
        runBlocking {
            repeat(60) { attempt ->
                try {
                    val response = http.get("")
                    assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 OK")
                    return@runBlocking
                } catch (e: Exception) {
                    if (attempt == 59) throw e
                    delay(500)
                }
            }
        }
        return t
    }

    @Test
    @Timeout(120)
    fun `child formatter wins for child files root formatter used otherwise`() = runBlocking {
        val rootFile = projectDir.resolve("root.txt")
        Files.writeString(rootFile, "hello root  ")
        val childFile = projectDir.resolve("child/child.txt")
        Files.writeString(childFile, "hello child  ")

        val t = startDaemonAndAwait()

        try {
            val childResponse = http.post("") {
                url { parameters.append("path", projectDir.relativize(childFile).toString()) }
                setBody("hello child  ")
            }
            assertEquals(
                HttpStatusCode.OK,
                childResponse.status,
                "Child file should be formatted successfully",
            )
            assertEquals(
                "hello child",
                childResponse.bodyAsText(),
                "Child formatter should not append trailing newline",
            )

            val rootResponse = http.post("") {
                url { parameters.append("path", projectDir.relativize(rootFile).toString()) }
                setBody("hello root  ")
            }
            assertEquals(
                HttpStatusCode.OK,
                rootResponse.status,
                "Root file should be formatted successfully",
            )
            assertEquals(
                "hello root\n",
                rootResponse.bodyAsText(),
                "Root formatter should enforce trailing newline",
            )
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }
}
