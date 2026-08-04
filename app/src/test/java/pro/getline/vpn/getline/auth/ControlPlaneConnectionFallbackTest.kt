package pro.getline.vpn.getline.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL

/**
 * Control-plane HTTP under a foreign lockdown VPN.
 *
 * Seeing a validated non-VPN network is not the same as being allowed to bind
 * it: the platform refuses the bind for this uid and every control-plane call
 * dies with SocketException — sign-in, refresh and import alike (#55). The
 * retry onto default routing is only safe while nothing has been written, so
 * these tests pin both halves: the retry happens, and the POST body is not
 * sent twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControlPlaneConnectionFallbackTest {

    @Test
    fun bindRefused_retriesOnDefaultRouting_andSendsBodyOnce() = runBlocking {
        val refused = FakeConnection(connectError = SocketException("bind refused"))
        val served = FakeConnection(
            responseCode = 200,
            body = """{"access_token":"a","refresh_token":"r","expires_in":60}""",
        )
        val opener = RecordingOpener(refused, served)

        val session = api(opener).exchangeDeviceKey("device-key")

        assertEquals(2, opener.opened.size)
        // First attempt is bound to the underlying network, the retry is not.
        assertNotNull("first attempt must be bound to a network", opener.opened[0])
        assertNull("retry must go through default routing", opener.opened[1])

        assertEquals("refused connection must be released", 1, refused.disconnects)
        assertEquals("nothing may be written before connect() succeeds", "", refused.written())

        assertEquals("""{"device_key":"device-key"}""", served.written())
        assertEquals(1, served.outputStreamCalls)
        assertEquals("POST", served.requestMethod)
        assertEquals("XMLHttpRequest", served.header("X-Requested-With"))
        assertEquals("application/json", served.header("Content-Type"))
        assertEquals(1, served.disconnects)

        assertEquals("a", session.accessToken)
        assertEquals("r", session.refreshToken)
        assertEquals(60L, session.expiresInSeconds)
    }

    @Test
    fun bindAccepted_doesNotOpenSecondConnection() = runBlocking {
        val served = FakeConnection(
            responseCode = 200,
            body = """{"access_token":"a","refresh_token":"r","expires_in":60}""",
        )
        val opener = RecordingOpener(served)

        api(opener).exchangeDeviceKey("device-key")

        assertEquals(1, opener.opened.size)
        assertNotNull(opener.opened[0])
        assertEquals(1, served.outputStreamCalls)
    }

    /**
     * The write phase is past the point of no return: a retry here could deliver
     * the same POST twice. The failure must surface instead.
     */
    @Test
    fun failureAfterConnect_doesNotRetry() = runBlocking {
        val broken = FakeConnection(outputStreamError = IOException("stream reset"))
        val opener = RecordingOpener(broken)

        try {
            api(opener).exchangeDeviceKey("device-key")
            fail("expected the write failure to surface")
        } catch (_: IOException) {
            // expected
        }

        assertEquals("a failed write must not be re-sent", 1, opener.opened.size)
        assertEquals(1, broken.disconnects)
    }

    private fun api(opener: RecordingOpener): RwpGetLineAuthApi {
        return RwpGetLineAuthApi(
            connectivityManager = connectivityManagerWithUnderlying(),
            openConnection = opener,
        )
    }

    /** A validated, non-VPN network — the one the API prefers and binds. */
    private fun connectivityManagerWithUnderlying(): ConnectivityManager {
        val cm = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = ShadowNetwork.newInstance(42)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        shadowOf(cm).addNetwork(
            network,
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_MOBILE,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        shadowOf(cm).setNetworkCapabilities(network, caps)
        return cm
    }

    private class RecordingOpener(
        vararg connections: FakeConnection,
    ) : (URL, Network?) -> HttpURLConnection {
        private val queue = ArrayDeque(connections.toList())
        val opened = mutableListOf<Network?>()

        override fun invoke(url: URL, network: Network?): HttpURLConnection {
            opened += network
            return queue.removeFirstOrNull()
                ?: throw AssertionError("unexpected connection #${opened.size} to $url")
        }
    }

    /**
     * Records what production did to the connection. Request properties are kept
     * here rather than in the JDK base class so the assertions do not depend on
     * URLConnection internals.
     */
    private class FakeConnection(
        private val connectError: IOException? = null,
        private val outputStreamError: IOException? = null,
        private val responseCode: Int = 200,
        private val body: String = "{}",
    ) : HttpURLConnection(URL("https://example.invalid/")) {
        private val headers = mutableMapOf<String, String>()
        private val sink = ByteArrayOutputStream()
        var disconnects = 0
            private set
        var outputStreamCalls = 0
            private set

        fun header(key: String): String? = headers[key]

        fun written(): String = sink.toString(Charsets.UTF_8.name())

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getRequestProperty(key: String): String? = headers[key]

        override fun connect() {
            connectError?.let { throw it }
        }

        override fun disconnect() {
            disconnects++
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream {
            outputStreamCalls++
            outputStreamError?.let { throw it }
            return sink
        }

        override fun getResponseCode(): Int = responseCode

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))

        override fun getErrorStream(): InputStream? = null
    }

    @Test
    fun fakeConnection_isWiredLikeTheRealOne() {
        // Guards the fixture itself: a silently unused body would make the
        // "sent once" assertions vacuous.
        val fake = FakeConnection()
        fake.doOutput = true
        fake.outputStream.write("x".toByteArray())
        assertTrue(fake.written() == "x")
    }
}
