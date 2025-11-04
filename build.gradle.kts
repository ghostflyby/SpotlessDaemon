/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */

plugins {
    alias(libs.plugins.changelog)
}

version = project.property("pluginVersion").toString()

tasks.getChangelog {
    this.noEmptySections = true
}