/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

plugins {
    id("kotlin-jvm")
    `kotlin-dsl`
}

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
