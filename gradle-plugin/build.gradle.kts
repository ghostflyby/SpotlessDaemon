/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    signing
    alias(libs.plugins.gradle.plugin.publish)
}

version = project.property("pluginVersion").toString()
group = project.property("pluginGroup").toString()

gradlePlugin {
    website = "https://github.com/ghostflyby/SpotlessDaemon"
    vcsUrl = "https://github.com/ghostflyby/SpotlessDaemon.git"
    plugins.register("spotlessDaemon") {
        id = "dev.ghostflyby.spotless.daemon"
        displayName = "Spotless Daemon"
        description = "Long running http daemon task for formatting files with Spotless"
        tags = setOf("style", "format", "spotless", "daemon", "code-quality")
        implementationClass = "dev.ghostflyby.spotless.daemon.SpotlessDaemon"
    }
}

dependencies {
    implementation(libs.spotless)
    implementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.ktor.client)
    testCompileOnly(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.params)
    testImplementation(gradleTestKit())
}

signing {
    val signingInMemoryKey: String? by project
    val signingInMemoryKeyPassword: String? by project
    useInMemoryPgpKeys(signingInMemoryKey, signingInMemoryKeyPassword)
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

tasks.publish {
    dependsOn(tasks.publishPlugins)
}

tasks.jar {
    archiveBaseName = "spotless-daemon-gradle-plugin"
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
    }
}
