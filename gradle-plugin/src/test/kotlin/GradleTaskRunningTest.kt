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
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds


class GradleTaskRunningTest(@param:TempDir val projectDir: File) {

    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }
    private val buildFile by lazy { projectDir.resolve("build.gradle.kts") }

    private val port by lazy { findFreePort() }

    @AutoClose
    private val http =
        HttpClient(CIO) {
            defaultRequest {
                url {
                    host = "localhost"
                    protocol = URLProtocol.HTTP
                    port = this@GradleTaskRunningTest.port
                }
            }
        }

    @BeforeEach
    fun setup() {
        buildFile.writeText(
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
    @Test
    fun `run without host config`() {
        val result: BuildResult = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withArguments(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME).forwardOutput().buildAndFail()
        val outcome = result.task(":${SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME}")?.outcome

        assertEquals(TaskOutcome.FAILED, outcome, "Should fail when neither port nor unixSocket set")
    }

    /**
     * @see [SpotlessDaemonTask.port]
     */
    @Test
    @Timeout(10)
    fun `run with port config`(): Unit =
        runBlocking {
            val t = thread(start = true) {
                GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(
                    ":spotlessDaemon",
                    "-Pdev.ghostflyby.spotless.daemon.port=$port",
                ).forwardOutput().build()
            }

            delay(3.seconds)

            val response = http.get("")
            assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 OK")

            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should stop successfully")
            t.join()
        }
}

