package pro.getline.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pro.getline.vpn.getline.auth.AuthCallbackParser
import pro.getline.vpn.getline.auth.AuthCallbackResult
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.PendingNativeAuth
import pro.getline.vpn.getline.auth.PendingNativeAuthStore
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi

/**
 * Exported deep-link receiver for package-id callbacks
 * (`${applicationId}:/oauth2redirect`).
 *
 * Dual payload:
 * - `?code=` + pending verifier → native exchange (Google/Telegram, Custom Tab / VIEW)
 * - `?auth_token=` → web-token establish (Telegram / edge page)
 *
 * Auth Tab may also deliver a package-scheme VIEW in parallel with ActivityResult;
 * if the sibling path already established a session, this activity only hands off.
 */
class NativeAuthCallbackActivity : Activity() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        scope.launch {
            try {
                val outcome = completeCallback()
                handoffToOnboarding(success = outcome)
            } catch (_: AbandonedNativeAuthCallback) {
                Log.i("native_auth_callback ignored=explicit_cancel")
            } catch (error: Exception) {
                Log.w("native_auth_callback failed kind=${error.javaClass.simpleName}")
                // Sibling Auth Tab may already have saved a session.
                val hasSession = runCatching {
                    GetLineSessionStore(this@NativeAuthCallbackActivity).hasRefreshToken()
                }.getOrDefault(false)
                handoffToOnboarding(success = hasSession)
            } finally {
                finish()
            }
        }
    }

    /** @return true when a native session exists after this call. */
    private suspend fun completeCallback(): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) {
            throw GetLineAuthException.InvalidCallback("Unexpected action")
        }
        val uri = intent?.data
            ?: throw GetLineAuthException.InvalidCallback("Missing callback URI")

        val parsed = AuthCallbackParser.parse(uri)
        val store = GetLineSessionStore(this)
        val repository = GetLineSessionRepository(
            api = RwpGetLineAuthApi(),
            store = store,
        )
        val pendingStore = PendingNativeAuthStore(this)

        when (parsed) {
            is AuthCallbackResult.NativeCode -> {
                val cancelledPredecessor = pendingStore.isCancellationMatching(
                    callbackUri = uri.toString(),
                    allowedProviders = NATIVE_CODE_PROVIDERS,
                )
                val pending = takeNativeCodePending(pendingStore, uri.toString())
                if (pending == null) {
                    if (store.hasRefreshToken()) {
                        Log.i("native_auth_callback_ok kind=code already_session")
                        return true
                    }
                    if (cancelledPredecessor) {
                        throw AbandonedNativeAuthCallback()
                    }
                    throw GetLineAuthException.InvalidCallback("No matching pending auth")
                }
                val established = establishNativeCodeWithCancellationFence(
                    pendingStore = pendingStore,
                    pending = pending,
                    code = parsed.code,
                    cancelledPredecessor = cancelledPredecessor,
                    exchange = repository::establishFromNativeCode,
                )
                if (!established) throw AbandonedNativeAuthCallback()
                Log.i(
                    "native_auth_callback_ok kind=code provider=${pending.provider} " +
                        "correlation=${pending.correlationId}",
                )
            }
            is AuthCallbackResult.WebToken -> {
                if (pendingStore.isCancellationMatching(
                        callbackUri = uri.toString(),
                        allowedProviders = setOf(AuthMethod.Telegram.name),
                    )
                ) {
                    if (store.hasRefreshToken()) {
                        Log.i("native_auth_callback_ok kind=web_token already_session")
                        return true
                    }
                    // Web-token callbacks carry no PKCE verifier or attempt id.
                    // Keep a newer pending untouched rather than let cancelled A own B.
                    throw AbandonedNativeAuthCallback()
                }
                val pending = pendingStore.takeIfMatches(
                    callbackUri = uri.toString(),
                    provider = AuthMethod.Telegram.name,
                )
                if (pending == null) {
                    if (store.hasRefreshToken()) {
                        Log.i("native_auth_callback_ok kind=web_token already_session")
                        return true
                    }
                    throw GetLineAuthException.InvalidCallback("No matching pending auth")
                }
                establishWeb(repository, pendingStore, pending, parsed.authToken)
            }
        }
        return true
    }

    private suspend fun establishWeb(
        repository: GetLineSessionRepository,
        pendingStore: PendingNativeAuthStore,
        pending: PendingNativeAuth,
        webToken: String,
    ) {
        try {
            repository.establishFromWebToken(webToken)
        } catch (e: Exception) {
            pendingStore.put(pending)
            throw e
        }
        pendingStore.clearCancellation()
        Log.i(
            "native_auth_callback_ok kind=web_token provider=${pending.provider} " +
                "correlation=${pending.correlationId}",
        )
    }

    private fun handoffToOnboarding(success: Boolean) {
        startActivity(
            Intent(this, GetLineOnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(GetLineOnboardingActivity.EXTRA_NATIVE_AUTH_SUCCESS, success)
                putExtra(GetLineOnboardingActivity.EXTRA_NATIVE_AUTH_HANDLED, true)
            },
        )
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}

/**
 * A code callback has no client attempt id. With a cancelled predecessor, PKCE
 * is the fence: a 4xx rejection means old code + new verifier, so restore the new
 * pending. Transport and server failures still use the normal failure handoff.
 */
internal suspend fun establishNativeCodeWithCancellationFence(
    pendingStore: PendingNativeAuthStore,
    pending: PendingNativeAuth,
    code: String,
    cancelledPredecessor: Boolean,
    exchange: suspend (code: String, verifier: String) -> Unit,
): Boolean {
    try {
        exchange(code, pending.verifier)
    } catch (e: Exception) {
        pendingStore.put(pending)
        val rejectedByPkceFence =
            e is GetLineAuthException.HttpFailure && e.code in 400..499
        if (e is CancellationException || !cancelledPredecessor || !rejectedByPkceFence) throw e
        return false
    }
    pendingStore.clearCancellation()
    return true
}

private class AbandonedNativeAuthCallback : Exception()

private val NATIVE_CODE_PROVIDERS = setOf(
    AuthMethod.Google.name,
    AuthMethod.Telegram.name,
)

/**
 * Accept native-code callbacks only for providers using the app-owned PKCE flow.
 * Peek first so an unknown provider never gets consumed by a generic callback.
 */
internal fun takeNativeCodePending(
    pendingStore: PendingNativeAuthStore,
    callbackUri: String,
): PendingNativeAuth? {
    val pending = pendingStore.peek() ?: return null
    if (pending.provider !in NATIVE_CODE_PROVIDERS) {
        return null
    }
    return pendingStore.takeIfMatches(
        callbackUri = callbackUri,
        provider = pending.provider,
    )
}
