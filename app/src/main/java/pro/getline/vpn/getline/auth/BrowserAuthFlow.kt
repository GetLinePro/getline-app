package pro.getline.vpn.getline.auth

import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.getlineui.model.GetLineProductState
import java.util.UUID

/**
 * One browser sign-in attempt, from provider tap to the post-login subscription
 * step, including the delivery races the attempt has to survive.
 *
 * Extracted from `GetLineOnboardingActivity` because this is where the attempt
 * state actually lives: the same session can be reported by the Auth Tab result
 * and by the exported package callback, and an explicit Cancel competes with
 * both. That ownership is not observable from the Activity's other flows and was
 * not reachable by any JVM test while it stayed in Activity fields.
 *
 * Owned here:
 * - [handoffSignal] — Custom Tab / external waits for [onDeepLinkHandoff];
 * - [postLoginPipeline] — single owner of the subscription step;
 * - [isCancelable] — whether pending auth can still be claimed by Cancel.
 *
 * Not owned here (stays with the Activity, reached through [Host]): the busy
 * guard shared with import/email flows, `retryTarget`, the browser launch, the
 * design surface, and the coroutine job the attempt runs on.
 */
internal class BrowserAuthFlow(
    private val sessionRepository: GetLineSessionRepository,
    private val pendingNativeAuthStore: PendingNativeAuthStore,
    private val authApi: GetLineAuthApi,
    private val host: Host,
    private val nativeCallbackUri: () -> String = { AppEnvironment.nativeCallbackUri },
) {
    /**
     * Everything the attempt needs from the Activity. Implemented by the
     * onboarding Activity; faked wholesale in tests.
     */
    interface Host {
        /** Screen-wide guard shared with import / email flows. */
        val isBusy: Boolean
        fun setBusy(busy: Boolean)

        /** Validated internet, not merely a connected transport. */
        fun isOnline(): Boolean

        fun markRetryBrowserLogin(method: AuthMethod)
        fun markRetryRefresh()
        fun markRetryImportPreferred()

        suspend fun setProductState(state: GetLineProductState)
        suspend fun setSessionEstablished(established: Boolean)
        suspend fun showProviders()

        /** Back to providers + entry product state; also resets the retry target. */
        suspend fun refreshEntryState()

        /** Mirrors [BrowserAuthFlow.isCancelable] onto the design. */
        fun setCancelable(cancelable: Boolean)

        /** `AuthFailed`, or `Offline` when the failure is a dead network. */
        fun authFailureState(): GetLineProductState

        suspend fun launchBrowser(
            method: AuthMethod,
            authUrl: String,
        ): BrowserAuthLaunchResult

        /** Post-login subscription half; throws on failure. */
        suspend fun importPreferredSubscription()

        /** Pre-session failure routes to a browser-login retry target. */
        suspend fun applyBrowserLoginFailure(method: AuthMethod, error: Exception)

        /** Post-session failure retries the subscription step only. */
        suspend fun applyPostLoginFailure(error: Exception)
    }

    private val postLoginPipeline = PostLoginPipeline()

    /** Custom Tab / external waits here; the callback completes it after exchange. */
    private var handoffSignal: CompletableDeferred<Unit>? = null

    private var cancelable = false

    /** True only while pending auth can still be atomically claimed by Cancel. */
    val isCancelable: Boolean
        get() = cancelable

    fun setCancelable(value: Boolean) {
        cancelable = value
        host.setCancelable(value)
    }

    /**
     * Atomically abandon the still-unclaimed attempt. False means a callback
     * already took pending and owns the result, so the Cancel tap is swallowed.
     */
    suspend fun claimCancel(): Boolean =
        withContext(Dispatchers.IO) { pendingNativeAuthStore.cancelPending() }

    /**
     * Runs one attempt for [method]. Holds the busy guard for its whole body,
     * including the post-login step when this attempt owns it.
     */
    suspend fun signIn(method: AuthMethod) {
        if (host.isBusy) return
        require(method.requiresBrowser()) {
            "AuthMethod.$method does not use browser auth"
        }

        host.markRetryBrowserLogin(method)
        if (!host.isOnline()) {
            host.setProductState(GetLineProductState.Offline)
            return
        }

        // Keep before reset and before any suspension: a Running post-login
        // pipeline owns busy, so the guard above must reject a second attempt.
        host.setBusy(true)
        handoffSignal = CompletableDeferred()
        postLoginPipeline.reset()
        host.setProductState(GetLineProductState.Loading)

        try {
            when (method) {
                AuthMethod.Google,
                AuthMethod.Telegram,
                -> signInNativePkce(method)
                AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
            }
        } catch (_: GetLineAuthException.Cancelled) {
            if (sessionRepository.hasSession()) {
                // Package VIEW completed while Auth Tab reported cancel.
                runPostLoginStep()
            } else {
                // Abandon attempt — drop pending (not an exchange re-put case).
                pendingNativeAuthStore.clearPending()
                host.refreshEntryState()
            }
        } catch (_: GetLineAuthException.VerificationFailed) {
            if (sessionRepository.hasSession()) {
                runPostLoginStep()
            } else {
                pendingNativeAuthStore.clearPending()
                host.markRetryBrowserLogin(method)
                host.setProductState(GetLineProductState.AuthFailed)
            }
        } catch (_: GetLineAuthException.InvalidCallback) {
            if (sessionRepository.hasSession()) {
                runPostLoginStep()
            } else {
                // Missing/foreign callback — not a re-put after take.
                pendingNativeAuthStore.clearPending()
                Log.w("browser_auth_invalid_callback method=${method.name}")
                host.markRetryBrowserLogin(method)
                host.setProductState(GetLineProductState.AuthFailed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            applyLaunchFailure(method, e)
        } finally {
            setCancelable(false)
            handoffSignal = null
            host.setBusy(false)
            if (postLoginPipeline.isDeferred) {
                if (sessionRepository.hasSession()) {
                    runPostLoginStep()
                } else {
                    // Keep a late package callback eligible to own the step.
                    postLoginPipeline.clearDeferred()
                }
            }
        }
    }

    /**
     * Deep-link attempt finished in the callback Activity: the session may already
     * be established (success) or the attempt failed (no session).
     *
     * [success] is the callback's own report and is only used for a diagnostic —
     * the persisted session decides.
     */
    suspend fun onDeepLinkHandoff(success: Boolean) {
        // The callback has already claimed pending; Cancel must no longer race it.
        setCancelable(false)

        if (sessionRepository.hasSession()) {
            // Includes: success handoff, or failure handoff after sibling Auth Tab won.
            if (host.isBusy) {
                postLoginPipeline.defer()
                handoffSignal?.complete(Unit)
                return
            }
            runPostLoginStep()
            return
        }
        if (success) {
            Log.w("native_auth_handoff success_flag_without_session")
        }
        val waitingBrowser = handoffSignal
        waitingBrowser?.complete(Unit)
        if (waitingBrowser != null) return
        host.markRetryRefresh()
        host.showProviders()
        host.setProductState(host.authFailureState())
    }

    /**
     * Post-login subscription step. Claimable once per attempt: whichever of the
     * Auth Tab result and the package callback gets here first owns it.
     *
     * Acquires the busy guard only when the caller does not already hold it.
     */
    suspend fun runPostLoginStep() {
        setCancelable(false)
        if (!postLoginPipeline.tryStart()) return
        val acquiredBusy = !host.isBusy
        if (acquiredBusy) host.setBusy(true)
        try {
            host.setProductState(GetLineProductState.Loading)
            host.markRetryImportPreferred()
            host.setSessionEstablished(true)
            host.importPreferredSubscription()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.applyPostLoginFailure(e)
        } finally {
            postLoginPipeline.finish()
            if (acquiredBusy) host.setBusy(false)
        }
    }

    /**
     * Browser provider: app-owned PKCE + native package callback.
     * Caller holds the busy guard; this method does not clear it.
     */
    private suspend fun signInNativePkce(method: AuthMethod) {
        val pkce = NativeAuthPkce.generate()
        val callbackUri = nativeCallbackUri()
        pendingNativeAuthStore.put(
            PendingNativeAuth(
                provider = method.name,
                verifier = pkce.verifier,
                callbackUri = callbackUri,
                createdAtMs = System.currentTimeMillis(),
                correlationId = UUID.randomUUID().toString(),
            ),
        )
        setCancelable(true)

        val start = authApi.startBrowserAuth(
            method = method,
            codeChallenge = pkce.challenge,
            appRedirect = callbackUri,
        )
        Log.i("browser_auth_launch method=${method.name}")
        val launchResult = host.launchBrowser(method = method, authUrl = start.authUrl)
        handleBrowserLaunchResult(
            method = method,
            launchResult = launchResult,
            attemptCallbackUri = callbackUri,
        )
    }

    private suspend fun handleBrowserLaunchResult(
        method: AuthMethod,
        launchResult: BrowserAuthLaunchResult,
        attemptCallbackUri: String?,
    ) {
        when (launchResult) {
            is BrowserAuthLaunchResult.Completed -> {
                setCancelable(false)
                when (val parsed = AuthCallbackParser.parse(launchResult.callbackUri)) {
                    is AuthCallbackResult.NativeCode -> {
                        val pendingUri = attemptCallbackUri ?: nativeCallbackUri()
                        val pending = pendingNativeAuthStore.takeIfMatches(
                            callbackUri = pendingUri,
                            provider = method.name,
                        )
                        if (pending != null) {
                            completeLoginFromNativeCode(parsed.code, pending)
                        } else if (sessionRepository.hasSession()) {
                            // Package VIEW already established via the callback Activity.
                            runPostLoginStep()
                        } else {
                            throw GetLineAuthException.InvalidCallback("No matching pending auth")
                        }
                    }
                    is AuthCallbackResult.WebToken -> {
                        val gateUri = attemptCallbackUri ?: nativeCallbackUri()
                        val pending = pendingNativeAuthStore.takeIfMatches(
                            callbackUri = gateUri,
                            provider = AuthMethod.Telegram.name,
                        )
                        if (pending != null) {
                            completeLoginFromWebTokenRestoringPending(parsed.authToken, pending)
                        } else if (sessionRepository.hasSession()) {
                            runPostLoginStep()
                        } else {
                            throw GetLineAuthException.InvalidCallback("No matching pending auth")
                        }
                    }
                }
            }
            BrowserAuthLaunchResult.AwaitingDeepLink -> {
                // Custom Tab / external: completion arrives via the callback Activity.
                handoffSignal?.await()
                if (sessionRepository.hasSession()) {
                    runPostLoginStep()
                } else {
                    // Callback Activity re-puts pending on exchange/network failure.
                    // Route through the generic failure path so Retry remains eligible.
                    throw GetLineAuthException.Protocol("Deep-link handoff failed")
                }
            }
            BrowserAuthLaunchResult.Cancelled -> {
                setCancelable(false)
                if (sessionRepository.hasSession()) {
                    runPostLoginStep()
                } else {
                    pendingNativeAuthStore.clearPending()
                    throw GetLineAuthException.Cancelled()
                }
            }
            BrowserAuthLaunchResult.VerificationFailed -> {
                setCancelable(false)
                if (sessionRepository.hasSession()) {
                    runPostLoginStep()
                } else {
                    pendingNativeAuthStore.clearPending()
                    throw GetLineAuthException.VerificationFailed()
                }
            }
            BrowserAuthLaunchResult.NoBrowser -> {
                setCancelable(false)
                pendingNativeAuthStore.clearPending()
                Log.w("browser_auth_no_browser method=${method.name}")
                host.markRetryBrowserLogin(method)
                host.setProductState(GetLineProductState.AuthFailed)
            }
            is BrowserAuthLaunchResult.Invalid -> {
                setCancelable(false)
                if (sessionRepository.hasSession()) {
                    runPostLoginStep()
                } else {
                    pendingNativeAuthStore.clearPending()
                    throw GetLineAuthException.InvalidCallback(launchResult.message)
                }
            }
        }
    }

    /**
     * Native PKCE post-callback (Auth Tab). On exchange failure re-puts the same
     * pending so a dual-delivery sibling (package VIEW) can still take it within
     * TTL. In-app Retry for exchange errors re-runs browser login (new pending),
     * not a browserless re-submit of the consumed code.
     * Caller holds the busy guard; this method does not clear it.
     */
    private suspend fun completeLoginFromNativeCode(
        code: String,
        pending: PendingNativeAuth,
    ) {
        try {
            sessionRepository.establishFromNativeCode(code, pending.verifier)
        } catch (e: Exception) {
            pendingNativeAuthStore.put(pending)
            throw e
        }
        pendingNativeAuthStore.clearCancellation()
        runPostLoginStep()
    }

    private suspend fun completeLoginFromWebTokenRestoringPending(
        webToken: String,
        pending: PendingNativeAuth,
    ) {
        try {
            sessionRepository.establishFromWebToken(webToken)
        } catch (e: Exception) {
            pendingNativeAuthStore.put(pending)
            throw e
        }
        pendingNativeAuthStore.clearCancellation()
        runPostLoginStep()
    }

    /**
     * Routes a throw from [signInNativePkce]. Post-session failures are handled
     * inside [runPostLoginStep], so a live session here belongs to the sibling
     * package callback and still needs one import attempt.
     */
    private suspend fun applyLaunchFailure(method: AuthMethod, error: Exception) {
        if (sessionRepository.hasSession()) {
            // Package VIEW established the session while the Auth Tab path threw.
            runPostLoginStep()
        } else {
            // Exchange/network failure: completeLoginFrom* may have re-put pending.
            // Do not clear — Retry within TTL can still use dual delivery / re-tap.
            host.applyBrowserLoginFailure(method, error)
        }
    }
}
