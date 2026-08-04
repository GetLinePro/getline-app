package pro.getline.vpn.cmfa

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.getline.GetLineBackendResult
import java.io.IOException

/**
 * Every product profile operation — import, reimport, config update, delete —
 * goes through this envelope, and its failure text is the only trace a field
 * failure leaves in the shareable report (#74).
 *
 * The two behaviours worth pinning: a cancelled screen must not be reported as
 * a backend failure, and the description must stay one bounded line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileBackendCallTest {

    @Test
    fun success_returnsValue_andReportsNothing() = runBlocking {
        val reported = mutableListOf<String>()

        val result = callProfileBackend(onUnavailable = reported::add) { "ok" }

        assertEquals(GetLineBackendResult.Success("ok"), result)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun failure_isUnavailable_andReportedOnce() = runBlocking {
        val reported = mutableListOf<String>()

        val result = callProfileBackend(onUnavailable = reported::add) {
            throw IOException("EOF")
        }

        assertEquals(GetLineBackendResult.Unavailable, result)
        assertEquals(1, reported.size)
        assertEquals("IOException msg=EOF", reported.single())
    }

    /**
     * The real cause must survive the trip through `withTimeout`. Coroutine
     * stacktrace recovery rethrows a copy with the original as its cause, so a
     * naive `cause` prints the exception's own class and drops the chain — the
     * exact information the failure line exists to carry.
     */
    @Test
    fun failure_keepsTheRealCauseAcrossTheCoroutineBoundary() = runBlocking {
        val reported = mutableListOf<String>()

        callProfileBackend(onUnavailable = reported::add) {
            throw IllegalStateException("profile not imported", IOException("EOF"))
        }

        assertEquals(
            "IllegalStateException cause=IOException msg=profile not imported",
            reported.single(),
        )
    }

    @Test
    fun timeout_isReportedAsTimeout_notAsFailure() = runBlocking {
        val reported = mutableListOf<String>()

        val result = callProfileBackend(timeoutMs = 5, onUnavailable = reported::add) {
            delay(5_000)
        }

        assertEquals(GetLineBackendResult.Unavailable, result)
        assertEquals("timeout", reported.single())
    }

    /**
     * Rotation or a closed screen cancels the caller. Collapsing that into
     * Unavailable would report a backend failure that never happened — and
     * would swallow the cancellation the coroutine machinery needs to see.
     */
    @Test
    fun cancellationFromBlock_isRethrown_andNotReported() = runBlocking {
        val reported = mutableListOf<String>()

        try {
            callProfileBackend(onUnavailable = reported::add) {
                throw CancellationException("screen closed")
            }
            fail("expected cancellation to propagate")
        } catch (_: CancellationException) {
            // expected
        }

        assertTrue(reported.isEmpty())
    }

    @Test
    fun parentCancellation_isRethrown_andNotReported() = runBlocking {
        val reported = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()

        coroutineScope {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                callProfileBackend(timeoutMs = 60_000, onUnavailable = reported::add) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            job.cancelAndJoin()
        }

        assertTrue("cancelled caller is not a backend failure", reported.isEmpty())
    }

    @Test
    fun description_keepsCauseAndStaysOnOneBoundedLine() {
        val described = describeFailure(
            IllegalStateException(
                "profile not imported\nBearer secret-value",
                IOException("unexpected end of stream"),
            ),
        )

        assertEquals(
            "IllegalStateException cause=IOException " +
                "msg=profile not imported Bearer secret-value",
            described,
        )
        assertFalse("report lines must not be split by the message", described.contains("\n"))
    }

    @Test
    fun description_truncatesLongMessages() {
        val described = describeFailure(IllegalArgumentException("x".repeat(500)))

        // 160-char budget plus the "IllegalArgumentException msg=" prefix.
        assertEquals(160 + "IllegalArgumentException msg=".length, described.length)
        assertTrue(described.startsWith("IllegalArgumentException msg=xxx"))
    }

    @Test
    fun description_survivesMissingMessageAndCause() {
        assertEquals("IOException", describeFailure(IOException()))
    }
}
