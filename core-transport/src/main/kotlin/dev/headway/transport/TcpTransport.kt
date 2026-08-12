/*
 * This file is part of Headway.
 * Copyright (C) 2026 The Headway Authors
 *
 * Headway is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Headway is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Headway. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.headway.transport

import dev.headway.protocol.io.Transport
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Real TCP sockets — the transport the AAP session runs on once the phone has
 * joined the car's Wi-Fi.
 *
 * On a real head unit the endpoint comes from the Bluetooth handshake; port 5288
 * is what the references use ([WirelessHandshake] documents the citation). On
 * Android the socket must additionally be created through the bound `Network`
 * object, because the car's access point has no internet and Android will not
 * route to it by default — that binding lives in `:app`, which supplies the
 * already-connected [Socket] to [wrap].
 */
object TcpTransport {

    /**
     * Wraps an already-connected socket.
     *
     * This is the entry point Android uses: `network.socketFactory.createSocket()`
     * produces a socket bound to the car's network, and it becomes a [Transport]
     * here without this module needing to know anything about Android.
     */
    fun wrap(socket: Socket): Transport {
        configure(socket)
        return StreamTransport(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            closeStreams = false,
            onClose = { runCatching { socket.close() } },
        )
    }

    /** Connects to a head unit and returns the transport. */
    fun connect(host: String, port: Int, connectTimeoutMillis: Int = 10_000): Transport {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        return wrap(socket)
    }

    /**
     * Listens for an inbound connection — the head unit's role, used by the
     * emulator.
     *
     * @param port 0 picks a free ephemeral port, which is what tests want so
     *   they never collide on a busy CI machine.
     */
    fun listen(port: Int = DEFAULT_PORT, backlog: Int = 1): Server = Server(ServerSocket(port, backlog))

    class Server(private val serverSocket: ServerSocket) : AutoCloseable {
        val port: Int get() = serverSocket.localPort

        /** Blocks until a peer connects. */
        fun accept(): Transport = wrap(serverSocket.accept())

        override fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private fun configure(socket: Socket) {
        // AAP is latency-sensitive: touch events and video frames are small
        // writes that must not wait for Nagle to coalesce them. CLAUDE.md's
        // budget is 250 ms touch-to-photon end to end, and Nagle alone can add
        // tens of milliseconds per message.
        socket.tcpNoDelay = true
        // The session must notice a head unit that went away, rather than
        // blocking forever -- reconnection depends on the read failing.
        socket.keepAlive = true
    }

    /**
     * The TCP port a wireless head unit listens on.
     *
     * Source: `aa-proxy-rs/src/config.rs` L25 (`TCP_SERVER_PORT: i32 = 5288`) and
     * `WirelessAndroidAutoDongle/.../common.cpp` L65
     * (`getenv("AAWG_PROXY_PORT", 5288)`).
     */
    const val DEFAULT_PORT: Int = 5288
}
