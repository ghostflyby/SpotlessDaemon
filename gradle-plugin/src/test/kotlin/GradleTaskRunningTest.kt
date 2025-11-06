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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds


@ParameterizedClass
@EnumSource(GradleTaskRunningTest.Kind::class)
class GradleTaskRunningTest(val kind: Kind) {


    val projectDir: File = Files.createTempDirectory("gradle-test").toFile().apply { deleteOnExit() }

    init {
        val buildFile = projectDir.resolve("build.gradle.kts")
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
    enum class Kind {
        UNIX, TCP
    }


    private val port by lazy { findFreePort() }

    private val unixSocketPath by lazy { projectDir.resolve("spotless-daemon.sock").absolutePath }

    private val http by lazy {
        HttpClient(CIO) {
            defaultRequest {
                if (kind == Kind.TCP)
                    url {
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

    @BeforeEach
    fun setupProject() {
        println("SETUP projectDir = $projectDir, exists=${projectDir.exists()}, canWrite=${projectDir.canWrite()}")
    }


    private fun startRunner() = thread(start = true) {
        println("THREAD START projectDir = $projectDir, exists=${projectDir.exists()}, canWrite=${projectDir.canWrite()}")

        check(projectDir.isDirectory && projectDir.canWrite()) {
            "projectDir invalid before GradleRunner: $projectDir"
        }

        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(
            "spotlessDaemon",
            if (kind == Kind.UNIX)
                "-Pdev.ghostflyby.spotless.daemon.unixsocket=$unixSocketPath"
            else "-Pdev.ghostflyby.spotless.daemon.port=$port",
        ).forwardOutput().build()
    }


    /**
     * @see [SpotlessDaemonTask.port]
     * @see [SpotlessDaemonTask.unixsocket]
     */
    @Test
    fun `run without host config`() {
        val result: BuildResult = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withArguments(SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME).buildAndFail()
        val outcome = result.task(":${SpotlessDaemon.SPOTLESS_DAEMON_TASK_NAME}")?.outcome

        assertEquals(TaskOutcome.FAILED, outcome, "Should fail when neither port nor unixSocket set")
    }

    @Test
    @Timeout(10)
    fun `health check`(): Unit =
        runBlocking {
            val t = startRunner()
            println("SERVER STARTED projectDir = $projectDir, exists=${projectDir.exists()}, canWrite=${projectDir.canWrite()}")

            delay(6.seconds)
            println("DELAY MADE projectDir = $projectDir, exists=${projectDir.exists()}, canWrite=${projectDir.canWrite()}")

            val response = http.get("")
            assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 OK")
            println("after response projectDir = $projectDir, exists=${projectDir.exists()}, canWrite=${projectDir.canWrite()}")

            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should stop successfully")
            println("before join projectDir = $projectDir, exists=${projectDir.exists()}, canWrite=${projectDir.canWrite()}")
            t.join()
        }
}

