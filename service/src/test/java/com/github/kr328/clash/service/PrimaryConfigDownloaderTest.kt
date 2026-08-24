package com.github.kr328.clash.service

import android.content.Context
import android.net.Network
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrimaryConfigDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private companion object {
        const val CONTRACT_VERSION = "9.9.9.Contract"
        const val SEEDED_HWID = "550e8400-e29b-41d4-a716-446655440000"
    }

    @Before
    fun installKnownPackageVersion() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName = CONTRACT_VERSION
        Shadows.shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun selectedNetwork_preservesRequestAndResponseContract() = runBlocking {
        val network = ShadowNetwork.newInstance(42)
        val opener = RecordingOpener(
            Response(
                body = "mixed-port: 7890\n",
                headers = mapOf(
                    "subscription-userinfo" to "upload=1; download=2; total=3",
                    "profile-update-interval" to "24",
                    "ETag" to "\"abc123\"",
                ),
            ),
        )
        val directory = temporaryFolder.newFolder("download")
        val downloader = downloader(opener, network)

        val result = downloader.download(
            context,
            "https://example.com/subscription",
            temporaryDirectory = directory,
        )

        val downloaded = result as PrimaryConfigFetchResult.Downloaded
        assertEquals(listOf(network), opener.networks)
        assertEquals("GET", opener.connections.single().requestMethod)
        assertEquals(false, opener.connections.single().instanceFollowRedirects)
        assertEquals(false, opener.connections.single().useCaches)
        assertTrue(opener.connections.single().doInput)
        assertTrue(opener.connections.single().connectTimeout in 1..60_000)
        assertTrue(opener.connections.single().readTimeout in 1..60_000)
        assertEquals(
            expectedUserAgent(),
            opener.connections.single().requestHeader("User-Agent"),
        )
        assertNull(opener.connections.single().requestHeader("If-None-Match"))
        assertEquals("mixed-port: 7890\n", downloaded.file.readText())
        assertEquals("upload=1; download=2; total=3", downloaded.metadata.subscriptionUserInfo)
        assertEquals("24", downloaded.metadata.profileUpdateInterval)
        assertEquals("\"abc123\"", downloaded.metadata.etag)
        assertNull(downloaded.metadata.tag)
        assertNull(downloaded.metadata.status)
        assertNull(downloaded.metadata.deviceLimit)

        val file = downloaded.file
        result.close()
        assertFalse(file.exists())
        assertEquals(1, opener.connections.single().disconnects)
    }

    @Test
    fun missingGetLineMarkers_stillAcceptsBody() = runBlocking {
        val opener = RecordingOpener(
            Response(
                body = "proxies: []\n",
                headers = mapOf("ETag" to "\"cmfa\""),
            ),
        )

        downloader(opener).download(
            context,
            "https://example.com/subscription",
            temporaryDirectory = temporaryFolder.newFolder("missing-markers"),
        ).use { result ->
            val downloaded = result as PrimaryConfigFetchResult.Downloaded
            assertEquals("proxies: []\n", downloaded.file.readText())
            assertEquals("\"cmfa\"", downloaded.metadata.etag)
        }
    }

    @Test
    fun unexpectedGetLineMarkers_stillAcceptsBody() = runBlocking {
        val opener = RecordingOpener(
            Response(
                body = "proxies: []\n",
                headers = mapOf(
                    "x-getline-profile" to "clash",
                    "x-getline-schema" to "99",
                    "ETag" to "\"other\"",
                ),
            ),
        )

        downloader(opener).download(
            context,
            "https://example.com/subscription",
            temporaryDirectory = temporaryFolder.newFolder("unexpected-markers"),
        ).use { result ->
            val downloaded = result as PrimaryConfigFetchResult.Downloaded
            assertEquals("proxies: []\n", downloaded.file.readText())
            assertEquals("\"other\"", downloaded.metadata.etag)
        }
    }

    @Test
    fun notModified_sendsGetLineUserAgent() = runBlocking {
        val opener = RecordingOpener(
            Response(code = 304, message = "Not Modified"),
        )

        downloader(opener).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = "\"same\"",
            temporaryDirectory = temporaryFolder.newFolder("304-ua"),
        ).use { result ->
            assertTrue(result is PrimaryConfigFetchResult.NotModified)
        }

        assertEquals(
            expectedUserAgent(),
            opener.connections.single().requestHeader("User-Agent"),
        )
        assertEquals("\"same\"", opener.connections.single().requestHeader("If-None-Match"))
    }

    @Test
    fun getlineDisplayHeaders_areStoredOn200And304() = runBlocking {
        val opener200 = RecordingOpener(
            Response(
                body = "proxies: []\n",
                headers = mapOf(
                    "ETag" to "\"v1\"",
                    "X-GetLine-Tag" to "  paid ",
                    "X-GetLine-Status" to "Active",
                    "X-GetLine-Device-Limit" to "10",
                ),
            ),
        )
        downloader(opener200).download(
            context,
            "https://example.com/subscription",
            temporaryDirectory = temporaryFolder.newFolder("tag-200"),
        ).use { result ->
            val downloaded = result as PrimaryConfigFetchResult.Downloaded
            assertEquals("paid", downloaded.metadata.tag)
            assertEquals("Active", downloaded.metadata.status)
            assertEquals(10, downloaded.metadata.deviceLimit)
        }

        val opener304 = RecordingOpener(
            Response(
                code = 304,
                message = "Not Modified",
                headers = mapOf(
                    "X-GetLine-Tag" to "LTEPLUS",
                    "X-GetLine-Status" to "  ",
                    "X-GetLine-Device-Limit" to "invalid",
                ),
            ),
        )
        downloader(opener304).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = "\"same\"",
            temporaryDirectory = temporaryFolder.newFolder("tag-304"),
        ).use { result ->
            val notModified = result as PrimaryConfigFetchResult.NotModified
            assertEquals("LTEPLUS", notModified.metadata.tag)
            assertNull(notModified.metadata.status)
            assertNull(notModified.metadata.deviceLimit)
        }
    }

    @Test
    fun profileWebPageUrl_isNotStoredInMetadata() = runBlocking {
        val opener = RecordingOpener(
            Response(
                body = "rules: []\n",
                headers = mapOf(
                    "ETag" to "\"v1\"",
                    "subscription-userinfo" to "upload=1; download=2; total=3",
                    "profile-update-interval" to "24",
                    "profile-web-page-url" to "https://example.com/sub/secret-token",
                    "x-getline-profile" to "subscription",
                    "x-getline-schema" to "1",
                ),
            ),
        )

        downloader(opener).download(
            context,
            "https://example.com/subscription",
            temporaryDirectory = temporaryFolder.newFolder("webpage-url"),
        ).use { result ->
            val downloaded = result as PrimaryConfigFetchResult.Downloaded
            assertEquals("\"v1\"", downloaded.metadata.etag)
            assertEquals("upload=1; download=2; total=3", downloaded.metadata.subscriptionUserInfo)
            assertEquals("24", downloaded.metadata.profileUpdateInterval)
            assertFalse(
                opener.connections.single().requestedResponseHeaders.any {
                    it.equals("profile-web-page-url", ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun noValidator_omitsIfNoneMatch() = runBlocking {
        val opener = RecordingOpener(Response(body = "rules: []\n"))

        downloader(opener).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = null,
            temporaryDirectory = temporaryFolder.newFolder("no-inm"),
        ).use { }

        assertNull(opener.connections.single().requestHeader("If-None-Match"))
    }

    @Test
    fun withValidator_sendsIfNoneMatch() = runBlocking {
        val opener = RecordingOpener(
            Response(
                body = "rules: []\n",
                headers = mapOf("ETag" to "\"new\""),
            ),
        )

        downloader(opener).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = "\"old\"",
            temporaryDirectory = temporaryFolder.newFolder("inm"),
        ).use { }

        assertEquals("\"old\"", opener.connections.single().requestHeader("If-None-Match"))
    }

    @Test
    fun notModified_returnsMetadataWithoutBodyFile() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 304,
                message = "Not Modified",
                headers = mapOf(
                    "ETag" to "\"same\"",
                    "subscription-userinfo" to "upload=9; download=8; total=7",
                    "profile-update-interval" to "12",
                ),
            ),
        )
        val directory = temporaryFolder.newFolder("304")

        val result = downloader(opener).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = "\"same\"",
            temporaryDirectory = directory,
        )

        assertTrue(result is PrimaryConfigFetchResult.NotModified)
        assertEquals("\"same\"", result.metadata.etag)
        assertEquals("upload=9; download=8; total=7", result.metadata.subscriptionUserInfo)
        assertEquals("12", result.metadata.profileUpdateInterval)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        result.close()
    }

    @Test
    fun notModified_withoutEtag_leavesMetadataEtagNull() = runBlocking {
        val opener = RecordingOpener(
            Response(code = 304, message = "Not Modified"),
        )

        val result = downloader(opener).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = "\"old\"",
            temporaryDirectory = temporaryFolder.newFolder("304-no-etag"),
        )

        assertTrue(result is PrimaryConfigFetchResult.NotModified)
        assertNull(result.metadata.etag)
        result.close()
    }

    @Test
    fun notModified_blankEtag_isIgnored() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 304,
                message = "Not Modified",
                headers = mapOf("ETag" to "   "),
            ),
        )

        val result = downloader(opener).download(
            context,
            "https://example.com/subscription",
            ifNoneMatch = "\"old\"",
            temporaryDirectory = temporaryFolder.newFolder("304-blank-etag"),
        )

        assertNull(result.metadata.etag)
        result.close()
    }

    @Test
    fun unexpected304_withoutCondition_isRejected() = runBlocking {
        val opener = RecordingOpener(
            Response(code = 304, message = "Not Modified"),
        )

        val error = expectIOException {
            downloader(opener).download(
                context,
                "https://example.com/subscription",
                ifNoneMatch = null,
                temporaryDirectory = temporaryFolder.newFolder("unexpected-304"),
            )
        }

        assertTrue(error.message.orEmpty().contains("304"))
    }

    @Test
    fun cleartextSource_isRejectedWithoutOpeningConnection() = runBlocking {
        val opener = RecordingOpener(Response())

        try {
            downloader(opener).download(
                context,
                "http://example.com/subscription",
                temporaryDirectory = temporaryFolder.newFolder("cleartext"),
            )
            fail("expected cleartext rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(opener.urls.isEmpty())
    }

    @Test
    fun basicAuth_isDecodedAndReappliedAcrossSameHostRedirect() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://example.com:443/final"),
            ),
            Response(body = "rules: []\n"),
        )

        downloader(opener).download(
            context,
            "https://user%3Aname:p%40ss@example.com:443/start",
            temporaryDirectory = temporaryFolder.newFolder("basic-auth"),
        ).use { }

        val expected = "Basic " + android.util.Base64.encodeToString(
            "user:name:p@ss".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        assertEquals(2, opener.connections.size)
        opener.connections.forEach {
            assertEquals(expected, it.requestHeader("Authorization"))
        }
    }

    @Test
    fun basicAuth_isRemovedWhenRedirectChangesPort() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://example.com:8443/final"),
            ),
            Response(body = "rules: []\n"),
        )

        downloader(opener).download(
            context,
            "https://user:password@example.com:443/start",
            temporaryDirectory = temporaryFolder.newFolder("basic-auth-port-change"),
        ).use { }

        assertEquals("Basic dXNlcjpwYXNzd29yZA==", opener.connections[0].requestHeader("Authorization"))
        assertNull(opener.connections[1].requestHeader("Authorization"))
    }

    @Test
    fun ifNoneMatch_isKeptAcrossSameOriginRedirect() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://example.com/final"),
            ),
            Response(code = 304, message = "Not Modified"),
        )

        downloader(opener).download(
            context,
            "https://example.com/start",
            ifNoneMatch = "\"v1\"",
            temporaryDirectory = temporaryFolder.newFolder("inm-redirect"),
        ).use { }

        assertEquals("\"v1\"", opener.connections[0].requestHeader("If-None-Match"))
        assertEquals("\"v1\"", opener.connections[1].requestHeader("If-None-Match"))
    }

    @Test
    fun ifNoneMatch_isRemovedWhenRedirectChangesPort() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://example.com:8443/final"),
            ),
            Response(body = "rules: []\n"),
        )

        downloader(opener).download(
            context,
            "https://example.com:443/start",
            ifNoneMatch = "\"v1\"",
            temporaryDirectory = temporaryFolder.newFolder("inm-port-change"),
        ).use { }

        assertEquals("\"v1\"", opener.connections[0].requestHeader("If-None-Match"))
        assertNull(opener.connections[1].requestHeader("If-None-Match"))
    }

    @Test
    fun ifNoneMatch_isTrimmedBeforeSend() = runBlocking {
        val opener = RecordingOpener(Response(body = "rules: []\n"))

        downloader(opener).download(
            context,
            "https://example.com/sub",
            ifNoneMatch = "  \"padded\"  ",
            temporaryDirectory = temporaryFolder.newFolder("inm-trim"),
        ).use { }

        assertEquals("\"padded\"", opener.connections.single().requestHeader("If-None-Match"))
    }

    @Test
    fun defaultTemporaryDirectory_isApplicationCache() = runBlocking {
        val opener = RecordingOpener(Response(body = "rules: []\n"))

        val result = downloader(opener).download(
            context,
            "https://example.com/subscription",
        )

        val downloaded = result as PrimaryConfigFetchResult.Downloaded
        assertEquals(context.cacheDir.canonicalFile, downloaded.file.parentFile?.canonicalFile)
        result.close()
        assertFalse(downloaded.file.exists())
    }

    @Test
    fun sameHostRedirect_allowsPortChangeAndTrailingDot() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://example.com.:8443/final"),
            ),
            Response(body = "rules: []\n"),
        )

        downloader(opener).download(
            context,
            "https://example.com/start",
            temporaryDirectory = temporaryFolder.newFolder("redirect"),
        ).use { result ->
            val downloaded = result as PrimaryConfigFetchResult.Downloaded
            assertEquals("rules: []\n", downloaded.file.readText())
        }

        assertEquals(2, opener.urls.size)
        assertEquals("https", opener.urls[1].protocol)
        assertEquals("example.com.", opener.urls[1].host)
        assertEquals(8443, opener.urls[1].port)
    }

    @Test
    fun crossHostRedirect_isRejectedWithoutTemporaryFile() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://other.example/final"),
            ),
        )
        val directory = temporaryFolder.newFolder("cross-host")

        expectIOException {
            downloader(opener).download(
                context,
                "https://example.com/start",
                temporaryDirectory = directory,
            )
        }

        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(1, opener.connections.single().disconnects)
    }

    @Test
    fun httpsDowngrade_isRejected() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 307,
                headers = mapOf("Location" to "http://example.com/final"),
            ),
        )

        expectIOException {
            downloader(opener).download(
                context,
                "https://example.com/start",
                temporaryDirectory = temporaryFolder.newFolder("downgrade"),
            )
        }
        Unit
    }

    @Test
    fun redirectLimit_matchesGoCheckRedirect() = runBlocking {
        val redirect = Response(code = 302, headers = mapOf("Location" to "/again"))
        val opener = RecordingOpener(*Array(10) { redirect })

        val error = expectIOException {
            downloader(opener).download(
                context,
                "https://example.com/start",
                temporaryDirectory = temporaryFolder.newFolder("redirect-limit"),
            )
        }

        assertTrue(error.message.orEmpty().contains("10 redirects"))
        assertEquals(10, opener.urls.size)
    }

    @Test
    fun non2xx_isRejectedWithoutTemporaryFile() = runBlocking {
        val opener = RecordingOpener(Response(code = 503, message = "Unavailable"))
        val directory = temporaryFolder.newFolder("non-2xx")

        val error = expectIOException {
            downloader(opener).download(
                context,
                "https://example.com/sub",
                temporaryDirectory = directory,
            )
        }

        assertTrue(error.message.orEmpty().contains("HTTP 503"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun selectedNetworkConnectFailure_retriesDefaultRouting() = runBlocking {
        val network = ShadowNetwork.newInstance(42)
        val opener = RecordingOpener(
            Response(connectError = IOException("bind refused")),
            Response(body = "rules: []\n"),
        )

        downloader(opener, network).download(
            context,
            "https://example.com/sub",
            temporaryDirectory = temporaryFolder.newFolder("fallback"),
        ).use { result ->
            val downloaded = result as PrimaryConfigFetchResult.Downloaded
            assertEquals("rules: []\n", downloaded.file.readText())
        }

        assertEquals(2, opener.networks.size)
        assertEquals(network, opener.networks[0])
        assertNull(opener.networks[1])
        assertEquals(1, opener.connections[0].disconnects)
    }

    @Test
    fun selectedNetworkConnectTimeout_reservesHalfBudgetForFallback() = runBlocking {
        var now = 0L
        val network = ShadowNetwork.newInstance(42)
        val opener = RecordingOpener(
            Response(
                connectError = SocketTimeoutException("bound timeout"),
                onConnect = { now = 30_000L },
            ),
            Response(body = "rules: []\n"),
        )
        val downloader = PrimaryConfigDownloader(
            openConnection = opener,
            pickNetwork = { network },
            elapsedRealtime = { now },
            readDeviceId = { SEEDED_HWID },
        )

        downloader.download(
            context,
            "https://example.com/sub",
            temporaryDirectory = temporaryFolder.newFolder("timeout-fallback"),
        ).use { }

        assertEquals(listOf(network, null), opener.networks)
        assertEquals(30_000, opener.connections[0].connectTimeout)
        assertEquals(30_000, opener.connections[1].connectTimeout)
    }

    @Test
    fun expiredBoundConnectFailure_reportsOriginalWithoutOpeningFallback() = runBlocking {
        var now = 0L
        val network = ShadowNetwork.newInstance(42)
        val opener = RecordingOpener(
            Response(
                connectError = SocketTimeoutException("original bound timeout"),
                onConnect = { now = 60_001L },
            ),
        )
        val downloader = PrimaryConfigDownloader(
            openConnection = opener,
            pickNetwork = { network },
            elapsedRealtime = { now },
            readDeviceId = { SEEDED_HWID },
        )

        val error = expectIOException {
            downloader.download(
                context,
                "https://example.com/sub",
                temporaryDirectory = temporaryFolder.newFolder("expired-fallback"),
            )
        }

        assertEquals("original bound timeout", error.message)
        assertEquals(1, opener.urls.size)
    }

    @Test
    fun readFailure_removesPartialTemporaryFile() = runBlocking {
        val opener = RecordingOpener(Response(readError = IOException("reset")))
        val directory = temporaryFolder.newFolder("partial")

        expectIOException {
            downloader(opener).download(
                context,
                "https://example.com/sub",
                temporaryDirectory = directory,
            )
        }

        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun seededDeviceId_sendsHwidAndDisplayHeaders() = runBlocking {
        val opener = RecordingOpener(Response(body = "rules: []\n"))

        downloader(opener, readDeviceId = { SEEDED_HWID }).download(
            context,
            "https://example.com/sub",
            temporaryDirectory = temporaryFolder.newFolder("hwid-seeded"),
        ).use { }

        assertHwidHeaders(opener.connections.single(), SEEDED_HWID)
        assertEquals(
            expectedUserAgent(),
            opener.connections.single().requestHeader("User-Agent"),
        )
    }

    @Test
    fun blankDeviceId_omitsHwidAndDisplayHeaders() = runBlocking {
        val opener = RecordingOpener(Response(body = "rules: []\n"))

        downloader(opener, readDeviceId = { "" }).download(
            context,
            "https://example.com/sub",
            temporaryDirectory = temporaryFolder.newFolder("hwid-omit"),
        ).use { }

        assertNoHwidHeaders(opener.connections.single())
        assertEquals(
            expectedUserAgent(),
            opener.connections.single().requestHeader("User-Agent"),
        )
    }

    @Test
    fun hwidHeaders_followUserAgentAcrossPortChangingRedirect() = runBlocking {
        val opener = RecordingOpener(
            Response(
                code = 302,
                headers = mapOf("Location" to "https://example.com:8443/final"),
            ),
            Response(body = "rules: []\n"),
        )

        downloader(opener, readDeviceId = { SEEDED_HWID }).download(
            context,
            "https://user:password@example.com:443/start",
            temporaryDirectory = temporaryFolder.newFolder("hwid-port-redirect"),
        ).use { }

        assertEquals(2, opener.connections.size)
        opener.connections.forEach { assertHwidHeaders(it, SEEDED_HWID) }
        assertEquals(
            "Basic dXNlcjpwYXNzd29yZA==",
            opener.connections[0].requestHeader("Authorization"),
        )
        assertNull(opener.connections[1].requestHeader("Authorization"))
    }

    @Test
    fun oneDeadline_coversConnectionAndResponse() = runBlocking {
        var now = 0L
        val opener = RecordingOpener(Response(onConnect = { now = 60_001L }))
        val downloader = PrimaryConfigDownloader(
            openConnection = opener,
            pickNetwork = { null },
            elapsedRealtime = { now },
            readDeviceId = { SEEDED_HWID },
        )

        try {
            downloader.download(
                context,
                "https://example.com/sub",
                temporaryDirectory = temporaryFolder.newFolder("deadline"),
            )
            fail("expected timeout")
        } catch (_: SocketTimeoutException) {
            // expected
        }
    }

    private fun expectedUserAgent(): String {
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        assertNotNull(version)
        assertTrue(version!!.isNotBlank())
        assertTrue(version != "unknown")
        return "GetLineVPN/$version"
    }

    private fun downloader(
        opener: RecordingOpener,
        network: Network? = null,
        readDeviceId: (Context) -> String = { SEEDED_HWID },
    ) = PrimaryConfigDownloader(
        openConnection = opener,
        pickNetwork = { network },
        elapsedRealtime = { 0L },
        readDeviceId = readDeviceId,
    )

    private fun assertHwidHeaders(connection: FakeConnection, hwid: String) {
        assertEquals(hwid, connection.requestHeader("x-hwid"))
        assertEquals("Android", connection.requestHeader("x-device-os"))
        assertEquals(Build.VERSION.RELEASE, connection.requestHeader("x-ver-os"))
        assertEquals(Build.MODEL, connection.requestHeader("x-device-model"))
    }

    private fun assertNoHwidHeaders(connection: FakeConnection) {
        assertNull(connection.requestHeader("x-hwid"))
        assertNull(connection.requestHeader("x-device-os"))
        assertNull(connection.requestHeader("x-ver-os"))
        assertNull(connection.requestHeader("x-device-model"))
    }

    private suspend fun expectIOException(block: suspend () -> Unit): IOException {
        return try {
            block()
            fail("expected IOException")
            throw AssertionError("unreachable")
        } catch (e: IOException) {
            e
        }
    }

    private data class Response(
        val code: Int = 200,
        val message: String = "OK",
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val connectError: IOException? = null,
        val readError: IOException? = null,
        val onConnect: () -> Unit = {},
    )

    private class RecordingOpener(
        vararg responses: Response,
    ) : (URL, Network?) -> HttpURLConnection {
        private val queue = ArrayDeque(responses.toList())
        val urls = mutableListOf<URL>()
        val networks = mutableListOf<Network?>()
        val connections = mutableListOf<FakeConnection>()

        override fun invoke(url: URL, network: Network?): HttpURLConnection {
            urls += url
            networks += network
            val response = queue.removeFirstOrNull()
                ?: throw AssertionError("unexpected connection #${urls.size} to $url")
            return FakeConnection(url, response).also(connections::add)
        }
    }

    private class FakeConnection(
        url: URL,
        private val response: Response,
    ) : HttpURLConnection(url) {
        private val requestHeaders = mutableMapOf<String, String>()
        val requestedResponseHeaders = mutableListOf<String>()
        var disconnects: Int = 0
            private set

        fun requestHeader(name: String): String? = requestHeaders[name]

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        override fun getRequestProperty(key: String): String? = requestHeaders[key]

        override fun connect() {
            response.onConnect()
            response.connectError?.let { throw it }
        }

        override fun disconnect() {
            disconnects++
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = response.code

        override fun getResponseMessage(): String = response.message

        override fun getHeaderField(name: String?): String? {
            if (name == null) return null
            requestedResponseHeaders += name
            return response.headers.entries.firstOrNull {
                it.key.equals(name, ignoreCase = true)
            }?.value
        }

        override fun getInputStream(): InputStream {
            response.readError?.let { error ->
                return object : InputStream() {
                    override fun read(): Int = throw error
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        throw error
                }
            }
            return ByteArrayInputStream(response.body.toByteArray(Charsets.UTF_8))
        }
    }
}
