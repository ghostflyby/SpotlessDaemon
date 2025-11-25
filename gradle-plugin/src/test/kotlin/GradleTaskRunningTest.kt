/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

import dev.ghostflyby.spotless.daemon.SpotlessDaemon
import dev.ghostflyby.spotless.daemon.SpotlessDaemonTask
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.div
import kotlin.time.Duration.Companion.seconds


@ParameterizedClass
@EnumSource(GradleTaskRunningTest.Kind::class)
class GradleTaskRunningTest(val kind: Kind, @param:TempDir var projectDir: Path) {


    var buildFile = projectDir / "build.gradle.kts"

    init {
        Files.createDirectories(projectDir)
        Files.createFile(buildFile)
        Files.writeString(
            buildFile,
            """
            plugins {
                id("com.diffplug.spotless")
                id("dev.ghostflyby.spotless.daemon")
            }
            
            """.trimIndent(),
        )
    }

    /**
     * @see [SpotlessDaemonTask.port]
     * @see [SpotlessDaemonTask.unixsocket]
     */
    enum class Kind {
        UNIX, TCP
    }


    private val port by lazy { findFreePort() }

    private val unixSocketPath by lazy { projectDir.resolve("spotless-daemon.sock").toString() }

    private val http by lazy {
        HttpClient(CIO) {
            defaultRequest {
                if (kind == Kind.TCP) url {
                    host = "127.0.0.1"
                    protocol = URLProtocol.HTTP
                    port = this@GradleTaskRunningTest.port
                }
                else {
                    url {
                        host = "127.0.0.1"
                        protocol = URLProtocol.HTTP
                    }
                    unixSocket(unixSocketPath)
                }
            }
        }
    }


    private fun startRunner() = thread(start = true) {
        try {
            GradleRunner.create().withProjectDir(projectDir.toFile())
                .forwardOutput().withPluginClasspath().withArguments(
                    "spotlessDaemon",
                    "--info",
                    "--stacktrace",
                    if (kind == Kind.UNIX) "-Pdev.ghostflyby.spotless.daemon.unixsocket=$unixSocketPath"
                    else "-Pdev.ghostflyby.spotless.daemon.port=$port",
                ).build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * @see [SpotlessDaemonTask.port]
     * @see [SpotlessDaemonTask.unixsocket]
     */
    @Test
    fun `run without host config`() {
        val s = ByteArrayOutputStream()
        val result: BuildResult =
            GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .forwardStdError(s.bufferedWriter())
                .withArguments(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME).buildAndFail()
        val outcome = result.task(":${SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME}")?.outcome

        assertEquals(TaskOutcome.FAILED, outcome, "Should fail when neither port nor unixSocket set")

        val str = s.toString()
        assertEquals(true, str.contains("Cannot query the value of task ':spotlessDaemon'"), str)
    }

    @Test
    @Timeout(30)
    fun `health check`(): Unit = runBlocking {
        val t = startRunner()

        try {

            delay(20.seconds)

            val response = http.get("")
            assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 OK")

            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
        } catch (e: Throwable) {
            e.printStackTrace()
            t.interrupt()
            throw e
        }
        t.join()
    }
}

