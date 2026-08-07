package com.github.kr328.clash.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.network.UnderlyingNetworkSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import kotlin.math.min

/**
 * Response headers shared by 200 and 304.
 *
 * Null means the header was absent (or blank for ETag). Callers map null to `""`
 * for the native report path; empty strings are treated as absent there and do
 * not emit SubscriptionInfo — so counters are not cleared (C4).
 * [etag] is opaque after [usableEtag] (trim + non-empty); no weak/strong parse.
 */
internal data class PrimaryConfigResponseMetadata(
    val etag: String?,
    val subscriptionUserInfo: String?,
    val profileUpdateInterval: String?,
)

/**
 * Outcome of a primary-config GET. Transport failures throw [IOException]
 * (or subclasses); there is no separate Failed type.
 */
internal sealed class PrimaryConfigFetchResult : Closeable {
    abstract val metadata: PrimaryConfigResponseMetadata

    class Downloaded(
        val file: File,
        override val metadata: PrimaryConfigResponseMetadata,
    ) : PrimaryConfigFetchResult() {
        override fun close() {
            if (file.exists() && !file.delete()) {
                Log.w("Unable to delete primary config temporary file")
            }
        }
    }

    class NotModified(
        override val metadata: PrimaryConfigResponseMetadata,
    ) : PrimaryConfigFetchResult() {
        override fun close() = Unit
    }
}

/**
 * Blank / missing ETag → null. Trims surrounding whitespace so stored and sent
 * values cannot carry header-injection padding. No weak/strong structure parse.
 */
internal fun usableEtag(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    return trimmed.takeIf { it.isNotEmpty() }
}

/** Downloads the bootstrap config without entering the Mihomo runtime tunnel. */
internal class PrimaryConfigDownloader(
    private val openConnection: (URL, Network?) -> HttpURLConnection =
        ::openPrimaryConfigConnection,
    private val pickNetwork: (Context) -> Network? = ::pickUnderlyingNetwork,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    suspend fun download(
        context: Context,
        source: String,
        ifNoneMatch: String? = null,
        temporaryDirectory: File = context.cacheDir,
    ): PrimaryConfigFetchResult = withContext(Dispatchers.IO) {
        val initialUrl = URL(source)
        require(initialUrl.protocol == "https") {
            "Unsupported primary config scheme ${initialUrl.protocol}"
        }

        temporaryDirectory.mkdirs()
        val deadline = elapsedRealtime() + TIMEOUT_MS
        val underlying = pickNetwork(context)
        val userAgent = userAgent(context)
        val authorization = basicAuthorization(initialUrl)
        val condition = usableEtag(ifNoneMatch)

        try {
            downloadFrom(
                initialUrl,
                underlying,
                temporaryDirectory,
                deadline,
                BOUND_CONNECT_TIMEOUT_MS.takeIf { underlying != null },
                userAgent,
                authorization,
                condition,
            )
        } catch (e: ConnectFailure) {
            if (underlying == null) throw e.cause

            // Binding a visible underlying network can be forbidden by a foreign
            // lockdown VPN. GET is still untouched at connect() failure, so retrying
            // once on default routing cannot duplicate a state-changing request.
            Log.w("primary_config_net fallback=default kind=${e.cause.javaClass.simpleName}")
            if (elapsedRealtime() >= deadline) throw e.cause
            try {
                downloadFrom(
                    initialUrl,
                    null,
                    temporaryDirectory,
                    deadline,
                    null,
                    userAgent,
                    authorization,
                    condition,
                )
            } catch (fallback: ConnectFailure) {
                throw fallback.cause
            }
        }
    }

    private fun downloadFrom(
        initialUrl: URL,
        network: Network?,
        directory: File,
        deadline: Long,
        connectTimeoutLimit: Int?,
        userAgent: String,
        authorization: String?,
        ifNoneMatch: String?,
    ): PrimaryConfigFetchResult {
        var currentUrl = initialUrl
        var redirects = 0

        while (true) {
            val connection = openConnection(currentUrl, network)
            try {
                val sameOriginHop = sameOrigin(initialUrl, currentUrl)
                // Match Authorization: only send conditional validator on same-origin hops.
                val conditionForHop = ifNoneMatch.takeIf { sameOriginHop }
                configure(
                    connection,
                    deadline,
                    connectTimeoutLimit,
                    userAgent,
                    authorization.takeIf { sameOriginHop },
                    conditionForHop,
                )
                try {
                    connection.connect()
                } catch (e: IOException) {
                    throw ConnectFailure(e)
                }
                remainingTimeout(deadline)
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in REDIRECT_CODES && location != null) {
                    // Match net/http CheckRedirect: via contains the requests
                    // already made, and len(via) >= 10 rejects the next hop.
                    if (redirects >= MAX_REQUESTS - 1) {
                        throw IOException("Stopped after $MAX_REQUESTS redirects")
                    }
                    val nextUrl = URL(currentUrl, location)
                    requireAllowedRedirect(initialUrl, nextUrl)
                    currentUrl = nextUrl
                    redirects++
                    continue
                }

                val metadata = responseMetadata(connection)

                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    if (conditionForHop == null) {
                        throw IOException(
                            "Fetch ${currentUrl.host}: unexpected HTTP 304 without If-None-Match",
                        )
                    }
                    logProfileMarkers(currentUrl, userAgent, connection)
                    Log.i(
                        "primary_config host=${currentUrl.host} inm=sent code=304 " +
                            "etag_hdr=${if (metadata.etag != null) "present" else "absent"}",
                    )
                    return PrimaryConfigFetchResult.NotModified(metadata)
                }

                if (code !in 200..299) {
                    throw IOException(
                        "Fetch ${currentUrl.host}: HTTP $code ${connection.responseMessage.orEmpty()}".trim(),
                    )
                }

                logProfileMarkers(currentUrl, userAgent, connection)

                val temporary = File.createTempFile("primary-config-", ".yaml", directory)
                try {
                    connection.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                // HttpURLConnection cannot retime a read already in
                                // flight. The socket timeout bounds that read; this
                                // check enforces the shared deadline between reads.
                                remainingTimeout(deadline)
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    Log.i(
                        "primary_config host=${currentUrl.host} " +
                            "inm=${if (conditionForHop != null) "sent" else "none"} code=$code " +
                            "etag_hdr=${if (metadata.etag != null) "present" else "absent"}",
                    )
                    return PrimaryConfigFetchResult.Downloaded(
                        file = temporary,
                        metadata = metadata,
                    )
                } catch (e: Exception) {
                    temporary.delete()
                    throw e
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun configure(
        connection: HttpURLConnection,
        deadline: Long,
        connectTimeoutLimit: Int?,
        userAgent: String,
        authorization: String?,
        ifNoneMatch: String?,
    ) {
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutLimit?.let {
            min(remainingTimeout(deadline), it)
        } ?: remainingTimeout(deadline)
        connection.readTimeout = remainingTimeout(deadline)
        connection.useCaches = false
        connection.doInput = true
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization)
        }
        if (ifNoneMatch != null) {
            connection.setRequestProperty("If-None-Match", ifNoneMatch)
        }
    }

    private fun responseMetadata(connection: HttpURLConnection): PrimaryConfigResponseMetadata {
        return PrimaryConfigResponseMetadata(
            etag = usableEtag(connection.getHeaderField("ETag")),
            subscriptionUserInfo = connection.getHeaderField("subscription-userinfo"),
            profileUpdateInterval = connection.getHeaderField("profile-update-interval"),
        )
    }

    private fun basicAuthorization(initialUrl: URL): String? {
        val rawUserInfo = initialUrl.userInfo ?: return null
        val separator = rawUserInfo.indexOf(':')
        val rawUsername = if (separator < 0) rawUserInfo else rawUserInfo.substring(0, separator)
        val rawPassword = if (separator < 0) "" else rawUserInfo.substring(separator + 1)
        val credentials = "${Uri.decode(rawUsername)}:${Uri.decode(rawPassword)}"
        val token = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    /**
     * Records which template answered. The panel selects it by User-Agent and marks
     * its own answer with these headers; once the body reaches the core, that choice
     * is no longer observable from the app. Never fails the download: the headers are
     * hand-edited in the panel, so a missing one is a config typo, not a bad config.
     */
    private fun logProfileMarkers(
        url: URL,
        userAgent: String,
        connection: HttpURLConnection,
    ) {
        val profile = connection.getHeaderField("x-getline-profile")
        val schema = connection.getHeaderField("x-getline-schema")
        if (profile == null) {
            Log.w("Fetch ${url.host}: no GetLine profile marker, sent $userAgent")
        } else {
            Log.i("Fetch ${url.host}: GetLine profile=$profile schema=${schema ?: "?"}, sent $userAgent")
        }
    }

    private fun userAgent(context: Context): String {
        // The package version is injected by Bridge into Go today. Reading it from
        // the service process keeps the exact backend content-negotiation token.
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName ?: "unknown"
        return "GetLineVPN/$version"
    }

    private fun remainingTimeout(deadline: Long): Int {
        val remaining = deadline - elapsedRealtime()
        if (remaining <= 0L) throw SocketTimeoutException("Primary config download timed out")
        return min(remaining, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    private fun requireAllowedRedirect(original: URL, next: URL) {
        val originalHost = canonicalHost(original.host)
        val nextHost = canonicalHost(next.host)
        if (originalHost.isEmpty() || nextHost.isEmpty() || originalHost != nextHost) {
            throw IOException(
                "Cross-host redirect rejected: ${original.host} -> ${next.host}",
            )
        }

        val nextScheme = next.protocol.lowercase(Locale.ROOT)
        if (nextScheme != "https") {
            throw IOException("HTTPS downgrade redirect rejected")
        }
    }

    private fun sameOrigin(first: URL, second: URL): Boolean {
        return first.protocol.equals(second.protocol, ignoreCase = true) &&
            canonicalHost(first.host) == canonicalHost(second.host) &&
            effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(url: URL): Int =
        url.port.takeIf { it >= 0 } ?: url.defaultPort

    private fun canonicalHost(host: String): String =
        host.trim().trimEnd('.').lowercase(Locale.ROOT)

    private class ConnectFailure(override val cause: IOException) : IOException(cause)

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val BOUND_CONNECT_TIMEOUT_MS = 30_000
        const val MAX_REQUESTS = 10
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun openPrimaryConfigConnection(url: URL, network: Network?): HttpURLConnection {
    return if (network != null) {
        network.openConnection(url) as HttpURLConnection
    } else {
        url.openConnection() as HttpURLConnection
    }
}

private fun pickUnderlyingNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager ?: return null
    return UnderlyingNetworkSelector.pickNetwork(connectivity)
}
