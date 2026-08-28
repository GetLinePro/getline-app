package com.github.kr328.clash.service.localproxy

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/** Bare TCP occupancy check before any Session override mutation. */
internal sealed interface LocalLanProxyPreflightOutcome {
    /** Nothing accepted the connection: safe to proceed with enable. */
    object Free : LocalLanProxyPreflightOutcome

    /** Something is already listening; enable must not proceed. */
    object Occupied : LocalLanProxyPreflightOutcome

    /** Timeout or other non-refusal error: fail closed, do not proceed. */
    object Ambiguous : LocalLanProxyPreflightOutcome
}

/** Result of a full, no-CONNECT SOCKS5 username/password negotiation. */
internal sealed interface LocalLanProxyProbeOutcome {
    /** The configured credentials were accepted: GetLine's listener is reachable. */
    object Authenticated : LocalLanProxyProbeOutcome

    /** The connector observed an explicit connection refusal. */
    object Refused : LocalLanProxyProbeOutcome

    /** TCP accepted but the protocol/credentials don't match: a different listener owns the port. */
    object OccupiedByOther : LocalLanProxyProbeOutcome

    /** Timeout or other inconclusive error: fail closed. */
    object Ambiguous : LocalLanProxyProbeOutcome
}

/**
 * Result of preparing, protecting, binding and connecting the probe socket.
 * Only [Refused] is positive evidence that no listener accepted the endpoint;
 * setup/routing failures must remain [Ambiguous].
 */
internal sealed interface LocalLanProxyConnectionOutcome {
    data class Connected(val socket: Socket) : LocalLanProxyConnectionOutcome
    object Refused : LocalLanProxyConnectionOutcome
    object Ambiguous : LocalLanProxyConnectionOutcome
}

private const val SOCKS5_VERSION = 0x05
private const val SOCKS5_METHOD_USERNAME_PASSWORD = 0x02
private const val SOCKS5_AUTH_VERSION = 0x01
private const val SOCKS5_AUTH_SUCCESS = 0x00

/**
 * Pure protocol classification over a socket that the caller has already
 * connected. Deliberately takes a typed `connect` lambda rather than
 * an endpoint/address: obtaining that socket is where
 * [LocalLanProxyRuntimeCoordinator] applies `VpnService.protect()` and binds
 * to the approved underlying `Network` (GetLine's own package is always
 * routed through its own TUN — see plan Current facts — so an unprotected,
 * unbound socket could loop back through Mihomo instead of testing the real
 * LAN path). Keeping that Android-specific setup out of this file is what
 * makes the classification logic itself testable with plain loopback sockets.
 *
 * Every negotiation stops right after the auth step and closes — it never
 * sends SOCKS5 CONNECT, so a probe never routes real traffic (see plan
 * Decisions).
 */
internal object LocalLanProxyProbe {
    fun classifyPreflight(connect: () -> LocalLanProxyConnectionOutcome): LocalLanProxyPreflightOutcome {
        return when (val connection = connectSafely(connect)) {
            is LocalLanProxyConnectionOutcome.Connected -> {
                connection.socket.close()
                LocalLanProxyPreflightOutcome.Occupied
            }
            LocalLanProxyConnectionOutcome.Refused -> LocalLanProxyPreflightOutcome.Free
            LocalLanProxyConnectionOutcome.Ambiguous -> LocalLanProxyPreflightOutcome.Ambiguous
        }
    }

    fun classifyAuth(
        connect: () -> LocalLanProxyConnectionOutcome,
        username: String,
        password: String,
    ): LocalLanProxyProbeOutcome {
        val connection = connectSafely(connect)
        val socket = when (connection) {
            is LocalLanProxyConnectionOutcome.Connected -> connection.socket
            LocalLanProxyConnectionOutcome.Refused -> return LocalLanProxyProbeOutcome.Refused
            LocalLanProxyConnectionOutcome.Ambiguous -> return LocalLanProxyProbeOutcome.Ambiguous
        }

        return socket.use { negotiate(it, username, password) }
    }

    private fun connectSafely(
        connect: () -> LocalLanProxyConnectionOutcome,
    ): LocalLanProxyConnectionOutcome = try {
        connect()
    } catch (e: IOException) {
        // The connector must opt in to Refused after observing ECONNREFUSED.
        // An untyped bind/protect/connect failure is never closure evidence.
        LocalLanProxyConnectionOutcome.Ambiguous
    }

    private fun negotiate(socket: Socket, username: String, password: String): LocalLanProxyProbeOutcome {
        return try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // VER, NMETHODS=1, METHODS=[username/password]
            output.write(byteArrayOf(SOCKS5_VERSION.toByte(), 1, SOCKS5_METHOD_USERNAME_PASSWORD.toByte()))
            output.flush()

            val serverVersion = input.readUnsignedByte()
            val serverMethod = input.readUnsignedByte()
            if (serverVersion != SOCKS5_VERSION || serverMethod != SOCKS5_METHOD_USERNAME_PASSWORD) {
                return LocalLanProxyProbeOutcome.OccupiedByOther
            }

            val userBytes = username.toByteArray(Charsets.US_ASCII)
            val passBytes = password.toByteArray(Charsets.US_ASCII)

            // RFC 1929: VER, ULEN, UNAME, PLEN, PASSWD
            output.write(byteArrayOf(SOCKS5_AUTH_VERSION.toByte(), userBytes.size.toByte()))
            output.write(userBytes)
            output.write(byteArrayOf(passBytes.size.toByte()))
            output.write(passBytes)
            output.flush()

            val authVersion = input.readUnsignedByte()
            val status = input.readUnsignedByte()
            if (authVersion == SOCKS5_AUTH_VERSION && status == SOCKS5_AUTH_SUCCESS) {
                LocalLanProxyProbeOutcome.Authenticated
            } else {
                LocalLanProxyProbeOutcome.OccupiedByOther
            }
        } catch (e: SocketTimeoutException) {
            LocalLanProxyProbeOutcome.Ambiguous
        } catch (e: IOException) {
            // Peer closed/reset mid-handshake: something answered TCP but
            // isn't speaking GetLine's protocol/credentials.
            LocalLanProxyProbeOutcome.OccupiedByOther
        }
    }
}
