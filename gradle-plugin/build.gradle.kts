import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */


plugins {
    alias(libs.plugins.kotlin)
    `kotlin-dsl`
    signing
}

version = project.property("pluginVersion").toString()

gradlePlugin {
    plugins {
        val spotlessDaemon by creating
        spotlessDaemon.apply {
            id = "dev.ghostflyby.spotless.daemon"
            implementationClass = "dev.ghostflyby.spotless.daemon.SpotlessDaemon"
        }
    }
}
repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.spotless)
    implementation(libs.bundles.ktor.server)
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

