/*
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Part of SpotlessDaemon
 */


internal fun findFreePort(): Int {
    java.net.ServerSocket(0).use { socket ->
        return socket.localPort
    }
}