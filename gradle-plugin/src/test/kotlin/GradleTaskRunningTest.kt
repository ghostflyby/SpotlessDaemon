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
import io.ktor.client.statement.*
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


@ParameterizedClass
@EnumSource(GradleTaskRunningTest.Kind::class)
class GradleTaskRunningTest(val kind: Kind, @param:TempDir val projectDir: Path) {


    val buildFile = projectDir / "build.gradle.kts"

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

            repositories {
                mavenCentral()
            }
            
            spotless {
                format("misc") {
                    target("*.txt")
                    trimTrailingWhitespace()
                    endWithNewline()
                }
                java {
                    target("*.java")
                    googleJavaFormat("1.22.0")
                }
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
    @Timeout(50)
    fun `health check`(): Unit = runBlocking {
        val t = startRunner()

        try {

            repeat(60) { // 60 attempts * 500ms = 30 seconds max
                try {
                    val response = http.get("")
                    assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 OK")
                    return@repeat
                } catch (e: Exception) {
                    if (it == 59) throw e // Last attempt failed
                    delay(500)
                }
            }
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
        } catch (e: Throwable) {
            e.printStackTrace()
            t.interrupt()
            throw e
        }
        t.join()
    }

    @Test
    @Timeout(60)
    fun `post missing path returns bad request`(): Unit = runBlocking {
        val t = startDaemonAndAwait()

        try {
            val response = http.post("") {
                setBody("example content")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "Should respond with 400 when path missing")
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }

    @Test
    @Timeout(60)
    fun `post path not covered returns not found`(): Unit = runBlocking {
        val uncovered = projectDir.resolve("uncovered.kt")
        Files.writeString(uncovered, "fun example() = 1")

        val t = startDaemonAndAwait()

        try {
            val response = http.post("") {
                url { parameters.append("path", projectDir.relativize(uncovered).toString()) }
                setBody("fun example() = 1")
            }
            assertEquals(
                HttpStatusCode.NotFound,
                response.status,
                "Should respond with 404 for files not covered by Spotless",
            )
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }

    @Test
    @Timeout(60)
    fun `post formats covered file and returns content`(): Unit = runBlocking {
        val targetFile = projectDir.resolve("sample.txt")
        Files.writeString(targetFile, "hello world  ")

        val t = startDaemonAndAwait()

        try {
            val response = http.post("") {
                url { parameters.append("path", projectDir.relativize(targetFile).toString()) }
                setBody("hello world  ")
            }
            assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 for covered files")
            assertEquals("hello world\n", response.bodyAsText(), "Should return formatted content")
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }

    @Test
    @Timeout(90)
    fun `post formats java file with external formatter dep`(): Unit = runBlocking {
        val targetFile = projectDir.resolve("Sample.java")
        val unformatted = "class Sample{void f( ){System.out.println(\"hi\");}}"
        Files.writeString(targetFile, unformatted)

        val t = startDaemonAndAwait()

        try {
            val action = suspend {
                val response = http.post("") {
                    url { parameters.append("path", projectDir.relativize(targetFile).toString()) }
                    setBody(unformatted)
                }
                assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 for covered java files")
                assertEquals(
                    """
                class Sample {
                  void f() {
                    System.out.println("hi");
                  }
                }
                
                """.trimIndent(),
                    response.bodyAsText(),
                    "Should return google-java-format output",
                )
            }
            // first time includes downloading the formatter dependency on task thread
            action()
            // second time on IO Dispatcher
            action()
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }

    @Test
    @Timeout(60)
    fun `get encoding missing path returns bad request`(): Unit = runBlocking {
        val t = startDaemonAndAwait()

        try {
            val response = http.get("/encoding")
            assertEquals(HttpStatusCode.BadRequest, response.status, "Should respond with 400 when path missing")
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }

    @Test
    @Timeout(60)
    fun `get encoding not covered returns not found`(): Unit = runBlocking {
        val uncovered = projectDir.resolve("uncovered.kt")
        Files.writeString(uncovered, "fun example() = 1")

        val t = startDaemonAndAwait()

        try {
            val response = http.get("/encoding") {
                url { parameters.append("path", projectDir.relativize(uncovered).toString()) }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                response.status,
                "Should respond with 404 for files not covered by Spotless",
            )
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }

    @Test
    @Timeout(60)
    fun `get encoding returns charset for covered file`(): Unit = runBlocking {
        val targetFile = projectDir.resolve("sample.txt")
        Files.writeString(targetFile, "hello world  ")

        val t = startDaemonAndAwait()

        try {
            val response = http.get("/encoding") {
                url { parameters.append("path", projectDir.relativize(targetFile).toString()) }
            }
            assertEquals(HttpStatusCode.OK, response.status, "Should respond with 200 for covered files")
            assertEquals("UTF-8", response.bodyAsText(), "Should return encoding name")
        } finally {
            val stop = http.post("/stop")
            assertEquals(HttpStatusCode.OK, stop.status, "Should respond with 200 OK on stop")
            t.join()
        }
    }
}
