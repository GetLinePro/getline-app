package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Exercises [LocalLanProxyProbe]'s pure protocol classification against real
 * loopback TCP fixtures — no Android framework needed, since the coordinator
 * owns everything protect()/Network-bound (see LocalLanProxyProbe's kdoc).
 * Mirrors the plan's fixture-based verification intent: a listening/occupied
 * fixture, a fixture speaking the exact GetLine protocol, and no listener at
 * all.
 */
class LocalLanProxyProbeTest {
    private fun connectTo(server: ServerSocket, timeoutMs: Int = 500): Socket {
        val socket = Socket()
        socket.soTimeout = timeoutMs
        socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), timeoutMs)
        return socket
    }

    @Test
    fun preflight_explicitRefusal_isFree() {
        val outcome = LocalLanProxyProbe.classifyPreflight { LocalLanProxyConnectionOutcome.Refused }

        assertEquals(LocalLanProxyPreflightOutcome.Free, outcome)
    }

    @Test
    fun preflight_untypedConnectFailure_isAmbiguous() {
        val outcome = LocalLanProxyProbe.classifyPreflight {
            throw IOException("protect or bind failed")
        }

        assertEquals(LocalLanProxyPreflightOutcome.Ambiguous, outcome)
    }

    @Test
    fun preflight_anyListener_isOccupied() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val accepted = Thread {
                runCatching { server.accept()?.close() }
            }.apply { start() }

            val outcome = LocalLanProxyProbe.classifyPreflight {
                LocalLanProxyConnectionOutcome.Connected(connectTo(server))
            }

            assertEquals(LocalLanProxyPreflightOutcome.Occupied, outcome)
            accepted.join(1000)
        } finally {
            server.close()
        }
    }

    @Test
    fun auth_explicitRefusal_isRefused() {
        val outcome = LocalLanProxyProbe.classifyAuth(
            connect = { LocalLanProxyConnectionOutcome.Refused },
            username = "getline",
            password = "s3cret",
        )

        assertEquals(LocalLanProxyProbeOutcome.Refused, outcome)
    }

    @Test
    fun auth_untypedConnectFailure_isAmbiguous() {
        val outcome = LocalLanProxyProbe.classifyAuth(
            connect = { throw IOException("protect or bind failed") },
            username = "getline",
            password = "s3cret",
        )

        assertEquals(LocalLanProxyProbeOutcome.Ambiguous, outcome)
    }

    @Test
    fun auth_matchingGetLineFixture_isAuthenticated() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val fixture = Thread {
                server.accept().use { conn ->
                    val input = DataInputStream(conn.getInputStream())
                    val output = DataOutputStream(conn.getOutputStream())

                    val version = input.readUnsignedByte()
                    val nMethods = input.readUnsignedByte()
                    repeat(nMethods) { input.readUnsignedByte() }
                    check(version == 5)

                    output.write(byteArrayOf(5, 2))
                    output.flush()

                    val authVersion = input.readUnsignedByte()
                    val userLen = input.readUnsignedByte()
                    val userBytes = ByteArray(userLen)
                    input.readFully(userBytes)
                    val passLen = input.readUnsignedByte()
                    val passBytes = ByteArray(passLen)
                    input.readFully(passBytes)
                    check(authVersion == 1)

                    val ok = String(userBytes) == "getline" && String(passBytes) == "s3cret"
                    output.write(byteArrayOf(1, if (ok) 0 else 1))
                    output.flush()
                }
            }.apply { start() }

            val outcome = LocalLanProxyProbe.classifyAuth(
                connect = { LocalLanProxyConnectionOutcome.Connected(connectTo(server)) },
                username = "getline",
                password = "s3cret",
            )

            fixture.join(1000)
            assertEquals(LocalLanProxyProbeOutcome.Authenticated, outcome)
        } finally {
            server.close()
        }
    }

    @Test
    fun auth_wrongCredentials_isOccupiedByOther() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val fixture = Thread {
                server.accept().use { conn ->
                    val input = DataInputStream(conn.getInputStream())
                    val output = DataOutputStream(conn.getOutputStream())

                    input.readUnsignedByte()
                    val nMethods = input.readUnsignedByte()
                    repeat(nMethods) { input.readUnsignedByte() }

                    output.write(byteArrayOf(5, 2))
                    output.flush()

                    input.readUnsignedByte()
                    val userLen = input.readUnsignedByte()
                    input.readFully(ByteArray(userLen))
                    val passLen = input.readUnsignedByte()
                    input.readFully(ByteArray(passLen))

                    // Always reject, regardless of what the client sent.
                    output.write(byteArrayOf(1, 1))
                    output.flush()
                }
            }.apply { start() }

            val outcome = LocalLanProxyProbe.classifyAuth(
                connect = { LocalLanProxyConnectionOutcome.Connected(connectTo(server)) },
                username = "getline",
                password = "wrong",
            )

            fixture.join(1000)
            assertEquals(LocalLanProxyProbeOutcome.OccupiedByOther, outcome)
        } finally {
            server.close()
        }
    }

    @Test
    fun auth_acceptsButNeverSpeaks_isAmbiguous() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val fixture = Thread {
                runCatching {
                    server.accept().use {
                        Thread.sleep(2000)
                    }
                }
            }.apply { start() }

            val outcome = LocalLanProxyProbe.classifyAuth(
                connect = {
                    LocalLanProxyConnectionOutcome.Connected(connectTo(server, timeoutMs = 300))
                },
                username = "getline",
                password = "s3cret",
            )

            assertEquals(LocalLanProxyProbeOutcome.Ambiguous, outcome)
            fixture.interrupt()
        } finally {
            server.close()
        }
    }
}
