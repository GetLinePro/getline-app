package pro.getline.vpn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.getline.auth.PendingNativeAuth
import pro.getline.vpn.getline.auth.PendingNativeAuthStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeAuthCallbackGateTest {
    private fun store(): PendingNativeAuthStore =
        PendingNativeAuthStore.testStore(RuntimeEnvironment.getApplication()).also {
            it.clear()
        }

    private fun pending(provider: String) = PendingNativeAuth(
        provider = provider,
        verifier = "verifier-value",
        callbackUri = AppEnvironment.nativeCallbackUri,
        createdAtMs = System.currentTimeMillis(),
        correlationId = "corr-1",
    )

    @Test
    fun nativeCodePending_acceptsTelegram() {
        val store = store()
        store.put(pending(AuthMethod.Telegram.name))

        assertNotNull(
            takeNativeCodePending(
                store,
                AppEnvironment.nativeCallbackUri + "?code=one-time-code",
            ),
        )
        assertNull(store.peek())
    }

    @Test
    fun nativeCodePending_acceptsGoogle() {
        val store = store()
        store.put(pending(AuthMethod.Google.name))

        assertNotNull(takeNativeCodePending(store, AppEnvironment.nativeCallbackUri))
        assertNull(store.peek())
    }

    @Test
    fun nativeCodePending_rejectsWithoutPending() {
        assertNull(takeNativeCodePending(store(), AppEnvironment.nativeCallbackUri))
    }

    @Test
    fun nativeCodePending_rejectsForeignProviderWithoutConsuming() {
        val store = store()
        store.put(pending("Email"))

        assertNull(takeNativeCodePending(store, AppEnvironment.nativeCallbackUri))
        assertNotNull(store.peek())
    }

    @Test
    fun explicitCancel_claimsPending_andLateNativeCallbackMatchesMarker() {
        val store = store()
        store.put(pending(AuthMethod.Google.name))

        assertTrue(store.cancelPending())
        assertNull(store.peek())
        assertTrue(
            store.isCancellationMatching(
                callbackUri = AppEnvironment.nativeCallbackUri + "?code=late",
                allowedProviders = setOf(AuthMethod.Google.name, AuthMethod.Telegram.name),
            ),
        )
        assertTrue(
            store.isCancellationMatching(
                callbackUri = AppEnvironment.nativeCallbackUri + "?code=late",
                allowedProviders = setOf(AuthMethod.Google.name, AuthMethod.Telegram.name),
            ),
        )
    }

    @Test
    fun callbackClaimWins_soCancelCannotDiscardCompletion() {
        val store = store()
        store.put(pending(AuthMethod.Telegram.name))

        assertNotNull(takeNativeCodePending(store, AppEnvironment.nativeCallbackUri))
        assertFalse(store.cancelPending())
    }

    @Test
    fun cancelledMarker_doesNotHideForeignCallback() {
        val store = store()
        store.put(pending(AuthMethod.Google.name))
        assertTrue(store.cancelPending())

        assertFalse(
            store.isCancellationMatching(
                callbackUri = "foreign.scheme:/oauth2redirect?code=late",
                allowedProviders = setOf(AuthMethod.Google.name, AuthMethod.Telegram.name),
            ),
        )
        assertFalse(
            store.isCancellationMatching(
                callbackUri = AppEnvironment.nativeCallbackUri + "?auth_token=late",
                allowedProviders = setOf(AuthMethod.Telegram.name),
            ),
        )
    }

    @Test
    fun lateCancelledCode_restoresNewPendingAfterPkceRejection() = runBlocking {
        val store = store()
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-a"))
        assertTrue(store.cancelPending())
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-b"))
        val cancelledPredecessor = store.isCancellationMatching(
            callbackUri = AppEnvironment.nativeCallbackUri + "?code=late-a",
            allowedProviders = setOf(AuthMethod.Google.name, AuthMethod.Telegram.name),
        )
        val claimedB = checkNotNull(
            takeNativeCodePending(store, AppEnvironment.nativeCallbackUri + "?code=late-a"),
        )

        val established = establishNativeCodeWithCancellationFence(
            pendingStore = store,
            pending = claimedB,
            code = "late-a",
            cancelledPredecessor = cancelledPredecessor,
            exchange = { _, _ ->
                throw GetLineAuthException.HttpFailure(400, "wrong PKCE verifier")
            },
        )

        assertFalse(established)
        assertEquals("attempt-b", store.peek()!!.correlationId)
        assertTrue(
            store.isCancellationMatching(
                callbackUri = AppEnvironment.nativeCallbackUri,
                allowedProviders = setOf(AuthMethod.Google.name, AuthMethod.Telegram.name),
            ),
        )
    }

    @Test
    fun currentCode_serverFailureWithFence_isPropagated() {
        val store = store()
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-a"))
        assertTrue(store.cancelPending())
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-b"))
        val claimedB = checkNotNull(
            takeNativeCodePending(store, AppEnvironment.nativeCallbackUri + "?code=code-b"),
        )

        assertThrows(GetLineAuthException.HttpFailure::class.java) {
            runBlocking {
                establishNativeCodeWithCancellationFence(
                    pendingStore = store,
                    pending = claimedB,
                    code = "code-b",
                    cancelledPredecessor = true,
                    exchange = { _, _ ->
                        throw GetLineAuthException.HttpFailure(503, "upstream unavailable")
                    },
                )
            }
        }
        assertEquals("attempt-b", store.peek()!!.correlationId)
    }

    @Test
    fun currentCode_transportFailureWithFence_isPropagated() {
        val store = store()
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-a"))
        assertTrue(store.cancelPending())
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-b"))
        val claimedB = checkNotNull(
            takeNativeCodePending(store, AppEnvironment.nativeCallbackUri + "?code=code-b"),
        )

        assertThrows(IOException::class.java) {
            runBlocking {
                establishNativeCodeWithCancellationFence(
                    pendingStore = store,
                    pending = claimedB,
                    code = "code-b",
                    cancelledPredecessor = true,
                    exchange = { _, _ -> throw IOException("timeout") },
                )
            }
        }
        assertEquals("attempt-b", store.peek()!!.correlationId)
    }

    @Test
    fun currentCode_successClearsCancelledPredecessorFence() = runBlocking {
        val store = store()
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-a"))
        assertTrue(store.cancelPending())
        store.put(pending(AuthMethod.Google.name).copy(correlationId = "attempt-b"))
        val claimedB = checkNotNull(
            takeNativeCodePending(store, AppEnvironment.nativeCallbackUri + "?code=code-b"),
        )

        val established = establishNativeCodeWithCancellationFence(
            pendingStore = store,
            pending = claimedB,
            code = "code-b",
            cancelledPredecessor = true,
            exchange = { _, _ -> Unit },
        )

        assertTrue(established)
        assertFalse(
            store.isCancellationMatching(
                callbackUri = AppEnvironment.nativeCallbackUri,
                allowedProviders = setOf(AuthMethod.Google.name, AuthMethod.Telegram.name),
            ),
        )
    }
}
