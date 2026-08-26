package pro.getline.vpn


import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.HelpActivity
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R as DesignR
import pro.getline.vpn.getlineui.GetLineOnboardingDesign
import pro.getline.vpn.getlineui.R as GetLineUiR
import pro.getline.vpn.getlineui.ToastDuration
import pro.getline.vpn.getlineui.model.GetLineImportStage
import pro.getline.vpn.getlineui.model.GetLineProductState
import pro.getline.vpn.getline.GetLineBackendProvider
import pro.getline.vpn.product.GetLineActivity
import pro.getline.vpn.getline.GetLineSubscriptionDraft
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.ImportAttempt
import pro.getline.vpn.getline.ImportWaitOutcome
import pro.getline.vpn.getline.ProductImportFlow
import pro.getline.vpn.getline.abandonPostLoginImportSession
import pro.getline.vpn.getline.auth.AuthTabRedirectMode
import pro.getline.vpn.getline.auth.BrowserAuthFlow
import pro.getline.vpn.getline.auth.BrowserAuthLauncher
import pro.getline.vpn.getline.auth.BrowserAuthLaunchResult
import pro.getline.vpn.getline.auth.browserRungCeilingFor
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.diagnostics.DiagnosticReportShare
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.GetLineSessionStorageException
import pro.getline.vpn.getline.auth.ManagedBindingSnapshot
import pro.getline.vpn.getline.auth.PendingNativeAuthStore
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi
import pro.getline.vpn.getline.auth.SubscriptionLinkMatcher
import pro.getline.vpn.getline.accountportal.AccountPortalLaunchResult
import pro.getline.vpn.getline.accountportal.AccountPortalUriPolicy
import pro.getline.vpn.getline.accountportal.DefaultAccountPortalLauncher
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.util.hasValidatedInternetConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class GetLineOnboardingActivity : GetLineActivity<GetLineOnboardingDesign>() {
    private val backend by lazy { GetLineBackendProvider.create(this) }
    private val authApi by lazy { RwpGetLineAuthApi() }
    private val sessionStore by lazy { GetLineSessionStore(this) }
    private val sessionRepository by lazy {
        GetLineSessionRepository(
            api = authApi,
            store = sessionStore,
        )
    }
    private val productImportFlow by lazy {
        ProductImportFlow(
            backend = backend,
            sessions = sessionRepository,
            host = ProductImportHost(),
        )
    }
    private val browserAuthLauncher = BrowserAuthLauncher()
    private val pendingNativeAuthStore by lazy { PendingNativeAuthStore(this) }
    /** Owns one browser sign-in attempt and its delivery races (see [BrowserAuthFlow]). */
    private val browserAuth by lazy {
        BrowserAuthFlow(
            sessionRepository = sessionRepository,
            pendingNativeAuthStore = pendingNativeAuthStore,
            authApi = authApi,
            host = BrowserAuthHost(),
        )
    }
    private val accountPortalLauncher = DefaultAccountPortalLauncher()
    private val imports = Channel<GetLineSubscriptionDraft>(Channel.UNLIMITED)
    private var busy = false
    /** Browser attempt runs beside the request loop so explicit Cancel can be received. */
    private var browserAuthJob: Job? = null
    /** Import wait can be left without discarding an established session/profile. */
    private var importWaitCancelable = false
    /** In-flight preferred-load / product-import wait; first complete() owns it. */
    private var importTerminal: ImportAttempt<*>? = null
    /**
     * Completes on each [onStart]; replaced on [onStop] so [awaitActivityStarted]
     * can suspend without polling or competing for [events].
     */
    private var activityStartedSignal = CompletableDeferred<Unit>()
    private var retryTarget: RetryTarget = RetryTarget.Refresh
    /** ElapsedRealtime when resend becomes allowed again (0 = no cooldown). */
    private var resendAvailableAtElapsedMs: Long = 0L
    private var resendTickerJob: Job? = null
    /**
     * In-progress email login (for restore after accidental subscription import).
     * [otpSent] true after a successful send — resume at OTP, not blank providers.
     */
    private var pendingEmailAuth: PendingEmailAuth? = null
    /** Opened from Home over a working link-only profile (see [EXTRA_LINK_ONLY_SIGN_IN]). */
    private var linkOnlySignIn = false

    override suspend fun main() {
        val design = GetLineOnboardingDesign(this, onDismissRequested = ::handleBackPressed)

        setContentDesign(design)
        // Read once: a later external import (onNewIntent) replaces the Activity
        // intent: this screen must not silently lose its exit affordance, and
        // QR / manual import stays hidden for the whole session of this entry.
        linkOnlySignIn = intent.getBooleanExtra(EXTRA_LINK_ONLY_SIGN_IN, false)
        design.setLinkOnlySignIn(linkOnlySignIn)
        // Resumed post-login step (mismatch dialog / import) starts with a live
        // session — login controls must stay hidden on its error states too.
        val initialBinding = try {
            sessionRepository.managedBindingSnapshot()
        } catch (_: GetLineSessionStorageException) {
            waitForSecureSessionStorage(design)
            return
        }
        val initialHasSession = initialBinding.hasSession
        design.setSessionEstablished(initialHasSession)

        val initialImport = intent.importRequest
        when {
            intent.getBooleanExtra(EXTRA_SESSION_STORAGE_RECOVERED, false) ||
                sessionStore.recoveredFromStorageFailure -> {
                retryTarget = RetryTarget.Refresh
                design.showProviders()
                design.setProductState(GetLineProductState.SessionStorageRecovered)
            }
            // #98: clean install with :background bind reject — show why, not NoProfile.
            // Retry → Refresh re-runs entry (and withProfile re-binds).
            intent.getBooleanExtra(EXTRA_BACKEND_UNAVAILABLE, false) -> {
                retryTarget = RetryTarget.Refresh
                design.showProviders()
                design.setProductState(GetLineProductState.BackendUnavailable)
            }
            // Deep-link native PKCE finished in NativeAuthCallbackActivity.
            intent.getBooleanExtra(EXTRA_NATIVE_AUTH_HANDLED, false) ->
                handleNativeAuthHandoff(intent)
            // Explicit external / deep-link import. Process death does not resume
            // an in-flight fetch; session/managed state below starts a new attempt.
            initialImport != null -> importSubscription(design, initialImport)
            // Session + link-only binding: mismatch dialog / import never finished.
            // Do not leave the user on providers with a live mixed state.
            initialBinding.needsPostLoginSubscriptionStep ->
                resumePreferredSubscription(design)
            else -> refreshEntryState(design)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when {
                        it != Event.ActivityStart || busy -> Unit
                        retryTarget == RetryTarget.Refresh -> refreshEntryState(design)
                        // Portal return / resume: re-read subscriptions without
                        // re-running trial mutation (ActivateTrial stays the target
                        // only for the explicit CTA / Offline Retry).
                        retryTarget == RetryTarget.ActivateTrial ->
                            resumePreferredSubscription(design)
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        GetLineOnboardingDesign.Request.LoginTelegram ->
                            startBrowserSignIn(AuthMethod.Telegram)
                        GetLineOnboardingDesign.Request.LoginGoogle ->
                            startBrowserSignIn(AuthMethod.Google)
                        GetLineOnboardingDesign.Request.LoginEmail ->
                            openEmailEntry(design)
                        is GetLineOnboardingDesign.Request.SendEmailOtp ->
                            sendEmailOtp(design, it.email)
                        is GetLineOnboardingDesign.Request.VerifyEmailOtp ->
                            verifyEmailOtp(design, it.email, it.code)
                        GetLineOnboardingDesign.Request.BackFromOtp ->
                            backFromOtp(design)
                        GetLineOnboardingDesign.Request.BackFromEmail ->
                            backFromEmail(design)
                        GetLineOnboardingDesign.Request.AddExistingSubscription ->
                            if (!busy) addExistingSubscription(design)
                        GetLineOnboardingDesign.Request.ScanQrCode ->
                            if (!busy) scanQrSubscription(design)
                        GetLineOnboardingDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        GetLineOnboardingDesign.Request.Retry ->
                            if (!busy) retry(design)
                        GetLineOnboardingDesign.Request.ActivateTrial ->
                            if (!busy) activateTrialAndImport(design)
                        GetLineOnboardingDesign.Request.OpenAccountPortal ->
                            openAccountPortal(design)
                        GetLineOnboardingDesign.Request.SendDiagnostics ->
                            DiagnosticReportShare.present(
                                activity = this@GetLineOnboardingActivity,
                                hasSession = sessionRepository.hasSession(),
                            )
                    }
                }
                imports.onReceive {
                    // singleTask + onNewIntent: while await-ing A, B sits in the
                    // channel. If busy, mark supersede; A handoff drains/runs B.
                    enqueueOrStartImport(design, it)
                }
            }
        }
    }

    /**
     * Persistent Keystore failure: remain usable as an error surface instead of
     * crash-looping. Each Retry is user-driven and performs one fresh open/reset.
     */
    private suspend fun waitForSecureSessionStorage(design: GetLineOnboardingDesign) {
        Log.w("session_storage outcome=unavailable")
        design.setProductState(GetLineProductState.SessionStorageUnavailable)
        while (isActive) {
            val reopened = select<Boolean> {
                events.onReceive { false }
                design.requests.onReceive { request ->
                    when (request) {
                        GetLineOnboardingDesign.Request.Retry -> {
                            try {
                                sessionRepository.hasSession()
                                true
                            } catch (_: GetLineSessionStorageException) {
                                false
                            }
                        }
                        GetLineOnboardingDesign.Request.OpenHelp -> {
                            startActivity(HelpActivity::class.intent)
                            false
                        }
                        else -> false
                    }
                }
            }
            if (reopened) {
                intent.putExtra(EXTRA_SESSION_STORAGE_RECOVERED, true)
                recreate()
                return
            }
            design.setProductState(GetLineProductState.SessionStorageUnavailable)
        }
    }

    /**
     * External import while another is in flight: do not drop B. Claim Cancel
     * on the current terminal; [drainAndContinueImport] starts B without joining
     * HTTP. If success already owns the terminal, B stays queued for drain.
     */
    private suspend fun enqueueOrStartImport(
        design: GetLineOnboardingDesign,
        request: GetLineSubscriptionDraft,
    ) {
        if (busy) {
            pendingExternalImport = request
            cancelActiveImport()
            return
        }
        importSubscription(design, request)
    }

    /** Newest external draft that arrived while [busy] (onNewIntent / channel). */
    private var pendingExternalImport: GetLineSubscriptionDraft? = null

    /**
     * Manual "add existing subscription": product URL dialog, then headless import.
     *
     * Cancel leaves product state and [retryTarget] alone — alternate import is
     * reachable from Offline / AuthFailed / ImportFailed / BackendUnavailable,
     * where Retry must still re-run the step that failed.
     */
    private suspend fun addExistingSubscription(design: GetLineOnboardingDesign) {
        if (busy) return

        val url = design.requestSubscriptionUrl(
            validator = { GetLineControlPlaneHostPolicy.isAllowedSubscriptionUrl(it) },
        ) ?: return

        importSubscription(
            design = design,
            request = GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = getString(DesignR.string.new_profile),
                source = url,
            ),
        )
    }

    /**
     * Product QR import. Same durable pipeline as [addExistingSubscription] after
     * host-policy validation and a host-only confirm dialog.
     */
    private suspend fun scanQrSubscription(design: GetLineOnboardingDesign) {
        if (busy) return

        when (val result = startActivityForResult(ScanQrCode(), Unit)) {
            is QrScanResult.Success -> {
                val content = result.content.trim()
                if (!GetLineControlPlaneHostPolicy.isAllowedSubscriptionUrl(content)) {
                    if (design.offerManualEntryAfterScanFailure(
                            GetLineUiR.string.get_line_import_link_rejected,
                        )
                    ) {
                        addExistingSubscription(design)
                    }
                    return
                }
                val host = GetLineControlPlaneHostPolicy.canonicalizeHost(
                    android.net.Uri.parse(content).host,
                )
                if (host == null) {
                    if (design.offerManualEntryAfterScanFailure(
                            GetLineUiR.string.get_line_import_link_rejected,
                        )
                    ) {
                        addExistingSubscription(design)
                    }
                    return
                }
                if (!design.confirmSubscriptionImport(host)) return
                importSubscription(
                    design = design,
                    request = GetLineSubscriptionDraft(
                        type = GetLineSubscriptionType.Url,
                        name = getString(DesignR.string.new_profile),
                        source = content,
                    ),
                )
            }
            QrScanResult.UserCanceled -> Unit
            QrScanResult.MissingPermission -> {
                if (design.offerManualEntryAfterScanFailure(
                        GetLineUiR.string.get_line_qr_no_camera_permission,
                    )
                ) {
                    addExistingSubscription(design)
                }
            }
            QrScanResult.Error -> {
                if (design.offerManualEntryAfterScanFailure(
                        GetLineUiR.string.get_line_qr_scan_failed,
                    )
                ) {
                    addExistingSubscription(design)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStartedSignal.complete(Unit)
    }

    override fun onStop() {
        // New deferred before super so waiters that resume after a flapping start
        // re-await the next onStart rather than treating a stale complete as ready.
        activityStartedSignal = CompletableDeferred()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        intent.importRequest?.let(imports::trySend)
        if (intent.getBooleanExtra(EXTRA_NATIVE_AUTH_HANDLED, false)) {
            launch { handleNativeAuthHandoff(intent) }
        }
    }

    /**
     * Deep-link path finished in [NativeAuthCallbackActivity]: session may already
     * be established (success) or the attempt failed (no session).
     */
    private suspend fun handleNativeAuthHandoff(intent: Intent) {
        // onNewIntent can arrive before main() installs the design.
        if (design == null) return
        val success = intent.getBooleanExtra(EXTRA_NATIVE_AUTH_SUCCESS, false)
        // One-shot: config change / recreate must not re-run import or paint failure.
        clearNativeAuthExtras(intent)
        browserAuth.onDeepLinkHandoff(success)
    }

    private fun clearNativeAuthExtras(intent: Intent) {
        intent.removeExtra(EXTRA_NATIVE_AUTH_HANDLED)
        intent.removeExtra(EXTRA_NATIVE_AUTH_SUCCESS)
        // Keep Activity intent in sync when extras arrived via setIntent(onNewIntent).
        if (intent === this.intent) return
        this.intent?.removeExtra(EXTRA_NATIVE_AUTH_HANDLED)
        this.intent?.removeExtra(EXTRA_NATIVE_AUTH_SUCCESS)
    }

    private suspend fun openEmailEntry(design: GetLineOnboardingDesign) {
        if (busy) return
        // If a code was already sent this session, resume OTP instead of blank email.
        val pending = pendingEmailAuth
        if (pending != null && pending.otpSent) {
            restoreEmailAuth(design)
            return
        }
        pendingEmailAuth = PendingEmailAuth(
            email = pending?.email.orEmpty(),
            otpSent = false,
        )
        retryTarget = RetryTarget.Refresh
        design.showEmailEntry(pending?.email?.takeIf { it.isNotBlank() })
        design.setProductState(GetLineProductState.AuthEmailEntry)
    }

    private suspend fun backFromOtp(design: GetLineOnboardingDesign) {
        if (busy) return
        val email = when (val t = retryTarget) {
            is RetryTarget.EmailVerify -> t.email
            is RetryTarget.EmailSend -> t.email
            else -> pendingEmailAuth?.email
        }
        // Clear code from memory when leaving OTP step. Do not keep EmailVerify.
        // Keep otpSent so re-open / import-cancel can return to OTP without resend.
        retryTarget = if (email != null) {
            RetryTarget.EmailSend(email)
        } else {
            RetryTarget.Refresh
        }
        if (email != null) {
            pendingEmailAuth = PendingEmailAuth(email = email, otpSent = true)
        }
        design.clearOtpCode()
        design.showEmailEntry(email)
        design.setProductState(GetLineProductState.AuthEmailEntry)
    }

    private suspend fun backFromEmail(design: GetLineOnboardingDesign) {
        if (busy) return
        retryTarget = RetryTarget.Refresh
        // Keep pendingEmailAuth + cooldown deadline; only leave the email UI.
        stopResendTicker()
        design.setResendCooldown(resendCooldownRemainingSeconds())
        design.showProviders()
        design.setProductState(entryProductState())
    }

    private suspend fun sendEmailOtp(
        design: GetLineOnboardingDesign,
        email: String,
    ) {
        if (busy) return

        val normalized = email.trim()
        if (normalized.isEmpty()) return

        retryTarget = RetryTarget.EmailSend(normalized)
        pendingEmailAuth = PendingEmailAuth(
            email = normalized,
            otpSent = pendingEmailAuth?.otpSent == true &&
                pendingEmailAuth?.email.equals(normalized, ignoreCase = true),
        )

        val cooldownLeft = resendCooldownRemainingSeconds()
        if (cooldownLeft > 0) {
            // Code already sent for this email → return to OTP, not a dead-end "wait".
            val pending = pendingEmailAuth
            if (pending != null &&
                pending.otpSent &&
                pending.email.equals(normalized, ignoreCase = true)
            ) {
                design.showOtpEntry(normalized, clearCode = false)
                design.setProductState(GetLineProductState.AuthEmailOtpSent)
                startResendTicker(design)
                return
            }
            design.setProductState(GetLineProductState.AuthRateLimited)
            startResendTicker(design)
            return
        }

        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        busy = true
        design.setProductState(GetLineProductState.Loading)

        try {
            authApi.sendEmailOtp(normalized)
            beginResendCooldown()
            pendingEmailAuth = PendingEmailAuth(email = normalized, otpSent = true)
            design.showOtpEntry(normalized, clearCode = true)
            design.setProductState(GetLineProductState.AuthEmailOtpSent)
            startResendTicker(design)
        } catch (e: GetLineAuthException) {
            retryTarget = RetryTarget.EmailSend(normalized)
            applyEmailAuthError(design, e)
        } catch (e: Exception) {
            retryTarget = RetryTarget.EmailSend(normalized)
            // AuthFailed now offers Send diagnostics — emit a discriminator first (GL-19).
            logPreSessionAuthFailed(e)
            design.setProductState(authFailureState())
        } finally {
            busy = false
        }
    }

    private suspend fun verifyEmailOtp(
        design: GetLineOnboardingDesign,
        email: String,
        code: String,
    ) {
        if (busy) return

        val normalizedEmail = email.trim()
        val normalizedCode = code.trim()
        if (normalizedEmail.isEmpty() || normalizedCode.isEmpty()) return

        // Code only in RetryTarget / field for this attempt — never Intent/SavedState.
        retryTarget = RetryTarget.EmailVerify(normalizedEmail, normalizedCode)
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        busy = true
        design.setProductState(GetLineProductState.Loading)

        try {
            val webToken = try {
                authApi.verifyEmailOtp(normalizedEmail, normalizedCode).webToken
            } catch (e: GetLineAuthException) {
                retryTarget = RetryTarget.EmailVerify(normalizedEmail, normalizedCode)
                applyEmailAuthError(design, e)
                return
            } catch (e: Exception) {
                retryTarget = RetryTarget.EmailVerify(normalizedEmail, normalizedCode)
                // AuthFailed now offers Send diagnostics — emit a discriminator first (GL-19).
                logPreSessionAuthFailed(e)
                design.setProductState(authFailureState())
                return
            }

            // OTP accepted (likely consumed). Resume with webToken on later failures —
            // never re-submit the same code via EmailVerify.
            design.clearOtpCode()
            pendingEmailAuth = null
            stopResendTicker()
            resendAvailableAtElapsedMs = 0L
            design.setResendCooldown(0)
            retryTarget = RetryTarget.CompleteFromWebToken(webToken)
            try {
                completeLoginFromWebToken(design, webToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetLineAuthException) {
                design.showProviders()
                applyLoginFailure(design, RetryTarget.CompleteFromWebToken(webToken), e)
            } catch (e: Exception) {
                design.showProviders()
                applyLoginFailure(design, RetryTarget.CompleteFromWebToken(webToken), e)
            }
        } finally {
            busy = false
        }
    }

    private suspend fun completeFromWebToken(
        design: GetLineOnboardingDesign,
        webToken: String,
    ) {
        if (busy) return

        retryTarget = RetryTarget.CompleteFromWebToken(webToken)
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        busy = true
        design.setProductState(GetLineProductState.Loading)
        try {
            completeLoginFromWebToken(design, webToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetLineAuthException) {
            applyLoginFailure(design, RetryTarget.CompleteFromWebToken(webToken), e)
        } catch (e: Exception) {
            applyLoginFailure(design, RetryTarget.CompleteFromWebToken(webToken), e)
        } finally {
            busy = false
        }
    }

    /**
     * Retry entry point for [RetryTarget.ImportPreferredSubscription]: the native
     * session already exists, so this must not re-run auth or re-mint a device key.
     */
    private suspend fun resumePreferredSubscription(design: GetLineOnboardingDesign) {
        if (busy) return
        // Set before any early return so Offline/Retry stay on this step, not providers.
        retryTarget = RetryTarget.ImportPreferredSubscription
        if (!sessionRepository.hasSession()) {
            // Session died meanwhile (e.g. 401 recovery logged us out).
            refreshEntryState(design)
            return
        }
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        busy = true
        design.setProductState(GetLineProductState.Loading)
        try {
            importPreferredSubscription(design)
        } catch (e: CancellationException) {
            // Mismatch dialog / Activity destroy must not map to AuthFailed UI.
            throw e
        } catch (e: GetLineAuthException) {
            applyLoginFailure(design, RetryTarget.ImportPreferredSubscription, e)
        } catch (e: Exception) {
            applyLoginFailure(design, RetryTarget.ImportPreferredSubscription, e)
        } finally {
            busy = false
        }
    }

    /**
     * Failure after the browser / OTP leg.
     *
     * With a persisted native session only the subscription step failed: keep the
     * session and offer a retry of that step, not of sign-in. Without one, fall
     * back to [preSessionTarget].
     */
    private suspend fun applyLoginFailure(
        design: GetLineOnboardingDesign,
        preSessionTarget: RetryTarget,
        error: Exception?,
    ) {
        if (error is GetLineSessionStorageException) {
            // saveSession already discarded and reopened the ambiguous store.
            // The web token remains memory-only; do not silently retry/mint keys.
            retryTarget = RetryTarget.Refresh
            design.setSessionEstablished(false)
            design.showProviders()
            design.setProductState(GetLineProductState.SessionStorageRecovered)
            return
        }
        // Never interpolate preSessionTarget: EmailSend carries an address.
        if (!sessionRepository.hasSession()) {
            // Used to be silent: every pre-session failure reached the user as a
            // bare "Couldn't sign in" with nothing in the log to say why.
            logPreSessionAuthFailed(error)
            retryTarget = preSessionTarget
            design.setProductState(authFailureState())
            return
        }

        if (error is GetLineAuthException.NoSubscription) {
            Log.i("post_session_no_subscription")
            retryTarget = RetryTarget.ActivateTrial
            design.setProductState(GetLineProductState.NoSubscription)
            return
        }
        logPostSessionSubscriptionFailed(error)
        retryTarget = RetryTarget.ImportPreferredSubscription
        design.setProductState(
            if (hasValidatedInternetConnection()) {
                GetLineProductState.ImportFailed
            } else {
                GetLineProductState.Offline
            },
        )
    }

    /**
     * Class name plus a non-secret discriminator for allowlisted diagnostics (GL-19).
     * HttpFailure/RateLimited messages hold raw response bodies — only the status code.
     * Protocol messages are authored constants (labels only, no URLs/tokens).
     */
    private fun logPreSessionAuthFailed(error: Exception?) {
        Log.w("pre_session_auth_failed kind=${authFailureKind(error)}${authFailureDetail(error)}")
    }

    private fun logPostSessionSubscriptionFailed(error: Exception?) {
        Log.w(
            "post_session_subscription_failed kind=${authFailureKind(error)}" +
                authFailureDetail(error),
        )
    }

    private fun authFailureKind(error: Exception?): String =
        error?.javaClass?.simpleName ?: "unknown"

    private fun authFailureDetail(error: Exception?): String = when (error) {
        is GetLineAuthException.HttpFailure -> " code=${error.code}"
        is GetLineAuthException.Protocol -> " reason=${error.message}"
        else -> ""
    }

    private fun startBrowserSignIn(
        method: AuthMethod,
    ) {
        if (busy || browserAuthJob?.isActive == true) return
        val job = launch { browserAuth.signIn(method) }
        browserAuthJob = job
        job.invokeOnCompletion {
            if (browserAuthJob === job) browserAuthJob = null
        }
    }

    /**
     * Shared post-login path: establish native session from web token, then import
     * preferred subscription (browser and email OTP converge here).
     *
     * Caller must hold [busy] = true; this method does not clear it.
     */
    private suspend fun completeLoginFromWebToken(
        design: GetLineOnboardingDesign,
        webToken: String,
    ) {
        sessionRepository.establishFromWebToken(webToken)
        // Session is persisted from here on: a later failure must retry only the
        // subscription step. Re-running auth mints another device key and adds
        // more RWP calls, which is how a single 429 turned into a loop.
        retryTarget = RetryTarget.ImportPreferredSubscription
        // Same rule for the UI: no login controls on post-session error states.
        design.setSessionEstablished(true)
        importPreferredSubscription(design)
    }

    /**
     * Subscription half of the post-login path: resolve which account subscription
     * to import (preferred, or the list item matching a link-only source).
     * Requires an established session; does not touch tokens unless the user
     * keeps a link-only profile (session discarded, binding kept).
     *
     * Does not call dashboard or activate trial — empty list surfaces
     * [GetLineProductState.NoSubscription] via [applyLoginFailure].
     */
    private suspend fun importPreferredSubscription(design: GetLineOnboardingDesign) {
        setImportWaitCancelable(design, cancelable = true)
        val load = try {
            when (
                val outcome = raceImportAttempt {
                    sessionRepository.loadPreferredSubscriptionWithList()
                }
            ) {
                ImportWaitOutcome.Cancelled -> {
                    if (!isFinishing && drainAndContinueImport(design)) {
                        return
                    }
                    if (!isFinishing) {
                        refreshEntryState(design)
                    }
                    return
                }
                is ImportWaitOutcome.Completed -> outcome.value
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!isFinishing && drainAndContinueImport(design)) {
                return
            }
            if (!isFinishing) {
                refreshEntryState(design)
            }
            return
        } finally {
            setImportWaitCancelable(design, false)
        }
        // Account-mismatch choice is intentionally non-cancelable. The concrete
        // subscription import enables Cancel again inside importSubscription.
        continueImportFromPreferredLoad(design, load)
    }

    /**
     * Explicit trial CTA: GET dashboard (prod may auto-activate), optional POST
     * /api/dashboard/trial, then import. Never runs without a user tap.
     */
    private suspend fun activateTrialAndImport(design: GetLineOnboardingDesign) {
        if (busy) return
        retryTarget = RetryTarget.ActivateTrial
        if (!sessionRepository.hasSession()) {
            refreshEntryState(design)
            return
        }
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        busy = true
        design.setProductState(GetLineProductState.Loading)
        try {
            design.setImportStage(GetLineImportStage.ActivatingTrial)
            when (val result = sessionRepository.activateTrialAndLoadPreferred()) {
                is GetLineSessionRepository.TrialActivationResult.Ready -> {
                    continueImportFromPreferredLoad(design, result.load)
                }
                is GetLineSessionRepository.TrialActivationResult.Unavailable -> {
                    val d = result.dashboard
                    Log.i(
                        "trial_unavailable enabled=${d.trialEnabled} " +
                            "available=${d.trialAvailable} paid=${d.trialPaid} " +
                            "recurring_only=${d.trialRecurringOnly} " +
                            "free_plan_enabled=${d.freePlanEnabled} " +
                            "free_plan_available=${d.freePlanAvailable}",
                    )
                    retryTarget = RetryTarget.ActivateTrial
                    design.setProductState(GetLineProductState.TrialUnavailable)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetLineAuthException.NoSubscription) {
            // Trial mutation may have succeeded while the reread is still empty
            // or the link failed allowlist — do not re-activate (would hit
            // TrialUnavailable). Retry only re-reads subscriptions.
            Log.w("trial_activate reread_empty")
            retryTarget = RetryTarget.ImportPreferredSubscription
            design.setProductState(
                if (hasValidatedInternetConnection()) {
                    GetLineProductState.ImportFailed
                } else {
                    GetLineProductState.Offline
                },
            )
        } catch (e: GetLineAuthException) {
            applyLoginFailure(design, RetryTarget.ActivateTrial, e)
        } catch (e: Exception) {
            applyLoginFailure(design, RetryTarget.ActivateTrial, e)
        } finally {
            busy = false
        }
    }

    private suspend fun openAccountPortal(design: GetLineOnboardingDesign) {
        val uri = try {
            AccountPortalUriPolicy.dashboardUri()
        } catch (_: Exception) {
            Log.w("account_portal_launch outcome=rejected_uri")
            design.showToast(
                GetLineUiR.string.get_line_account_portal_open_failed_title,
                ToastDuration.Long,
            )
            return
        }
        when (accountPortalLauncher.open(this, uri)) {
            AccountPortalLaunchResult.Launched,
            AccountPortalLaunchResult.AlreadyInProgress -> Unit
            AccountPortalLaunchResult.NoBrowserAvailable,
            AccountPortalLaunchResult.RejectedUri,
            is AccountPortalLaunchResult.Failed -> {
                Log.w("account_portal_launch outcome=failed")
                design.showToast(
                    GetLineUiR.string.get_line_account_portal_open_failed_title,
                    ToastDuration.Long,
                )
            }
        }
    }

    private suspend fun continueImportFromPreferredLoad(
        design: GetLineOnboardingDesign,
        load: GetLineSessionRepository.PreferredSubscriptionLoad,
    ) {
        // Capture prior selection before loading; load does not overwrite store.
        val binding = sessionRepository.managedBindingSnapshot()
        val previousSubscriptionId = binding.subscriptionId
        val managedUuid = binding.managedProfileUuid
        val managedSource = binding.managedProfileSource
        val all = load.all

        val linkOnly = binding.provenance == ManagedBindingSnapshot.Provenance.LinkOnly
        // Match the concrete list item (may be non-preferred). Import that item
        // so a secondary-link profile is not silently rewritten to preferred.
        val matchedItem = if (linkOnly) {
            SubscriptionLinkMatcher.findMatch(managedSource, all)
        } else {
            null
        }
        val linkMatch = matchedItem != null
        if (linkOnly) {
            Log.i("link_match matched=$linkMatch account_items=${all.size}")
        }

        if (linkOnly && !linkMatch) {
            when (design.confirmAccountMismatch()) {
                GetLineOnboardingDesign.MismatchChoice.KeepLinkOnly -> {
                    sessionRepository.discardSessionKeepingSubscription()
                    backend.navigation.openHome()
                    finish()
                    return
                }
                GetLineOnboardingDesign.MismatchChoice.UseAccount -> Unit
            }
        }

        val subscription = matchedItem ?: load.preferred
        val source = subscription.subscriptionLink
            ?: throw GetLineAuthException.Protocol("Missing subscription link")
        // preferred is validated inside loadPreferredSubscriptionWithList; matched
        // secondary items are not — re-check before import (env isolation).
        GetLineControlPlaneHostPolicy.requireSubscriptionUrl(source)

        val draft = GetLineSubscriptionDraft(
            type = GetLineSubscriptionType.Url,
            name = subscription.displayName
                ?: getString(GetLineUiR.string.get_line_subscription_profile_name),
            source = source,
        )

        importSubscription(
            design = design,
            request = draft,
            alreadyBusy = true,
            subscriptionIdToRemember = subscription.id,
        )
    }

    private suspend fun importSubscription(
        design: GetLineOnboardingDesign,
        request: GetLineSubscriptionDraft,
        alreadyBusy: Boolean = false,
        subscriptionIdToRemember: String? = null,
    ) {
        if (busy && !alreadyBusy) {
            pendingExternalImport = request
            cancelActiveImport()
            return
        }

        // User-driven link import (not post-login) can leave email OTP mid-flow.
        val userDrivenImport = !alreadyBusy && subscriptionIdToRemember == null
        val emailToRestore = if (userDrivenImport) pendingEmailAuth else null
        val cancelState = design.productState
        val cancelRetryTarget = retryTarget

        retryTarget = RetryTarget.ImportSubscription(
            request = request,
            subscriptionIdToRemember = subscriptionIdToRemember,
        )
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        if (!alreadyBusy) {
            busy = true
        }
        setImportWaitCancelable(design, cancelable = true)
        design.setProductState(GetLineProductState.Loading)

        try {
            val terminal = productImportFlow.run(
                request = request,
                subscriptionIdToRemember = subscriptionIdToRemember,
                onImportWaitFinished = {
                    setImportWaitCancelable(design, cancelable = false)
                },
                onProgress = { stage -> design.setImportStage(stage) },
            )

            when (terminal) {
                ProductImportFlow.Outcome.Cancelled -> {
                    if (!isFinishing && drainAndContinueImport(design)) {
                        return
                    }
                    if (isFinishing) return
                    if (emailToRestore != null) {
                        restoreEmailAuth(design)
                    } else if (sessionRepository.hasSession()) {
                        retryTarget = cancelRetryTarget
                        design.setProductState(cancelState)
                    } else {
                        refreshEntryState(design)
                    }
                }
                is ProductImportFlow.Outcome.Superseded -> {
                    startQueuedImport(design, terminal.replacement)
                    return
                }
                is ProductImportFlow.Outcome.Imported -> {
                    pendingEmailAuth = null
                    finishImportToHome(design)
                }
                is ProductImportFlow.Outcome.ActivationFailed -> {
                    terminal.retry?.let { retry ->
                        retryTarget = RetryTarget.ImportSubscription(
                            request = retry.request,
                            subscriptionIdToRemember = retry.subscriptionIdToRemember,
                        )
                    }
                    if (terminal.unavailable) {
                        Log.w("import_terminal activate_unavailable")
                        design.setProductState(GetLineProductState.BackendUnavailable)
                    } else {
                        Log.w("import_terminal activate_failed")
                        design.setProductState(GetLineProductState.ImportFailed)
                    }
                }
                is ProductImportFlow.Outcome.Failed -> {
                    if (drainAndContinueImport(design)) {
                        return
                    }
                    if (emailToRestore != null) {
                        restoreEmailAuth(design)
                    } else {
                        design.setProductState(
                            if (!hasValidatedInternetConnection()) {
                                GetLineProductState.Offline
                            } else {
                                GetLineProductState.ImportFailed
                            }
                        )
                    }
                }
            }
        } finally {
            setImportWaitCancelable(design, false)
            if (!alreadyBusy) {
                busy = false
            }
        }
    }

    /** Producer/cancel race for preferred-subscription loading. */
    private suspend fun <T> raceImportAttempt(
        onLost: suspend (T) -> Unit = {},
        produce: suspend () -> T,
    ): ImportWaitOutcome<T> {
        val attempt = ImportAttempt<T>()
        importTerminal = attempt
        val ioJob = launch(Dispatchers.IO) {
            try {
                val value = produce()
                if (!attempt.tryComplete(value)) {
                    withContext(NonCancellable) { onLost(value) }
                }
            } catch (cancelled: CancellationException) {
                attempt.tryCancel()
            } catch (error: Throwable) {
                attempt.tryFail(error)
            }
        }
        var delivered = false
        try {
            val result = attempt.await()
            delivered = attempt.markDelivered()
            if (!delivered) {
                throw CancellationException("Waiter abandoned")
            }
            return result
        } finally {
            if (!delivered) {
                val orphaned = attempt.abandonWaiter()
                if (orphaned != null) {
                    withContext(NonCancellable) { onLost(orphaned) }
                }
            }
            if (importTerminal === attempt) importTerminal = null
            ioJob.cancel()
        }
    }

    /** True when either the preferred load or product import owns Cancel. */
    private fun cancelActiveImport(): Boolean =
        productImportFlow.cancelActiveImport() || importTerminal?.tryCancel() == true

    /**
     * Drain channel + [pendingExternalImport]. Starts the newest draft if any.
     * @return true if a replacement import was started (caller must not open Home).
     */
    private suspend fun drainAndContinueImport(design: GetLineOnboardingDesign): Boolean {
        val draft = takeQueuedImport() ?: return false
        startQueuedImport(design, draft)
        return true
    }

    private fun takeQueuedImport(): GetLineSubscriptionDraft? {
        var latest: GetLineSubscriptionDraft? = pendingExternalImport
        pendingExternalImport = null
        while (true) {
            val next = imports.tryReceive().getOrNull() ?: break
            latest = next
        }
        return latest
    }

    private suspend fun startQueuedImport(
        design: GetLineOnboardingDesign,
        draft: GetLineSubscriptionDraft,
    ) {
        importSubscription(
            design = design,
            request = draft,
            alreadyBusy = true,
        )
    }

    /**
     * UI handoff after a successful live-waiter activation. Headless completion
     * only commits the managed binding; Home repair activates it on the next launch.
     *
     * Must not be called when [drainAndContinueImport] already took over.
     * Drain again after VPN permission: onNewIntent can land while the system
     * dialog / start is suspended, and that draft would otherwise die with finish().
     */
    private suspend fun finishImportToHome(
        design: GetLineOnboardingDesign,
    ) {
        // Last chance before VPN: onNewIntent may have raced after post-await drain.
        if (drainAndContinueImport(design)) {
            return
        }
        // Import/activation can finish while HOME has this Activity stopped.
        // Do not launch permission UI or navigate until it is foregrounded again.
        awaitActivityStarted()
        // Waiting for foreground is another suspension point where onNewIntent
        // can enqueue a newer import; it still wins over the Home handoff.
        if (drainAndContinueImport(design)) {
            return
        }
        design.setProductState(GetLineProductState.Loading)
        startVpnWithPermission()
        // onNewIntent during permission/start lands in [imports] while we were
        // suspended — drain before leaving Onboarding.
        if (drainAndContinueImport(design)) {
            return
        }
        awaitActivityStarted()
        if (drainAndContinueImport(design)) {
            return
        }
        backend.navigation.openHome()
        finish()
    }

    /**
     * Suspend while the Activity is stopped. Cancelled with the Activity MainScope.
     *
     * Must not call [events.receive]: [main]'s select is the sole consumer of that
     * channel. When [busy] is true it still receives and drops [Event.ActivityStart],
     * so a second receive() races main and can hang forever after Custom Tab return.
     * Use [activityStartedSignal] completed from [onStart] instead.
     */
    private suspend fun awaitActivityStarted() {
        while (!activityStarted) {
            activityStartedSignal.await()
        }
    }

    /** Resume in-progress email login (OTP preferred when a code was already sent). */
    private suspend fun restoreEmailAuth(design: GetLineOnboardingDesign) {
        val pending = pendingEmailAuth
        if (pending == null || pending.email.isBlank()) {
            retryTarget = RetryTarget.Refresh
            refreshEntryState(design)
            return
        }
        retryTarget = RetryTarget.EmailSend(pending.email)
        if (pending.otpSent) {
            design.showOtpEntry(pending.email, clearCode = true)
            design.setProductState(GetLineProductState.AuthEmailOtpSent)
            startResendTicker(design)
        } else {
            design.showEmailEntry(pending.email)
            design.setProductState(GetLineProductState.AuthEmailEntry)
        }
    }

    private suspend fun retry(design: GetLineOnboardingDesign) {
        when (val target = retryTarget) {
            RetryTarget.Refresh -> refreshEntryState(design)
            is RetryTarget.BrowserLogin ->
                startBrowserSignIn(target.method)
            is RetryTarget.EmailSend ->
                sendEmailOtp(design, target.email)
            is RetryTarget.EmailVerify ->
                verifyEmailOtp(design, target.email, target.code)
            is RetryTarget.CompleteFromWebToken ->
                completeFromWebToken(design, target.webToken)
            RetryTarget.ImportPreferredSubscription ->
                resumePreferredSubscription(design)
            RetryTarget.ActivateTrial ->
                activateTrialAndImport(design)
            is RetryTarget.ImportSubscription ->
                importSubscription(
                    design = design,
                    request = target.request,
                    subscriptionIdToRemember = target.subscriptionIdToRemember,
                )
        }
    }

    override fun handleBackPressed() {
        if (tryCancelBrowserAuth()) return
        if (tryCancelImportWait()) return
        if (busy) return
        val design = design
        if (design != null && design.tryNavigateEmailAuthBack()) {
            return
        }
        super.handleBackPressed()
    }

    private fun setImportWaitCancelable(
        design: GetLineOnboardingDesign,
        cancelable: Boolean,
    ) {
        importWaitCancelable = cancelable
        design.setImportWaitCancelable(cancelable)
    }

    /**
     * Cancel wins only while pending is still unclaimed. If a callback already
     * took it, that callback owns completion and the cancel tap is swallowed.
     */
    private fun tryCancelBrowserAuth(): Boolean {
        val design = design ?: return false
        val job = browserAuthJob
        if (!browserAuth.isCancelable ||
            job?.isActive != true ||
            retryTarget !is RetryTarget.BrowserLogin
        ) {
            return false
        }
        browserAuth.setCancelable(false)
        launch {
            if (browserAuth.claimCancel()) {
                job.cancelAndJoin()
                refreshEntryState(design)
            } // Otherwise the callback already claimed pending and owns the result.
        }
        return true
    }

    /**
     * Explicit Cancel of the visible import wait. Manual import restores prior
     * product state in the waiter. Post-login Cancel abandons the new session:
     * link-only keeps the existing subscription and opens Home; cold onboarding
     * logs out and returns to providers.
     */
    private fun tryCancelImportWait(): Boolean {
        if (!importWaitCancelable) return false
        val design = design ?: return false
        val postLogin = sessionRepository.hasSession() &&
            (
                retryTarget == RetryTarget.ImportPreferredSubscription ||
                    (retryTarget as? RetryTarget.ImportSubscription)
                        ?.subscriptionIdToRemember != null
            )
        setImportWaitCancelable(design, false)
        if (!cancelActiveImport()) {
            return true
        }
        if (postLogin) {
            abandonPostLoginImportSession(sessionRepository, linkOnlySignIn)
            design.setSessionEstablished(false)
            if (linkOnlySignIn) {
                backend.navigation.openHome()
                finish()
            }
        }
        return true
    }

    private suspend fun refreshEntryState(design: GetLineOnboardingDesign) {
        retryTarget = RetryTarget.Refresh
        // Do not clear pendingEmailAuth — user may re-open Email to finish OTP.
        design.showProviders()
        design.setProductState(entryProductState())
    }

    private fun entryProductState(): GetLineProductState {
        return if (hasValidatedInternetConnection()) {
            GetLineProductState.NoProfile
        } else {
            GetLineProductState.Offline
        }
    }

    private suspend fun startVpnWithPermission() {
        try {
            val vpnRequest = backend.vpn.start()
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest,
                )
                if (result.resultCode == RESULT_OK) {
                    backend.vpn.start()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Active profile is already set; Home can retry VPN start.
        }
    }

    /**
     * Stay-in-place email error mapping.
     * Does not log email, OTP, or tokens.
     */
    private suspend fun applyEmailAuthError(
        design: GetLineOnboardingDesign,
        error: GetLineAuthException,
    ) {
        when (error) {
            is GetLineAuthException.InvalidOtp -> {
                // Stay on OTP; leave field for edit.
                design.setProductState(GetLineProductState.AuthInvalidOtp)
            }
            is GetLineAuthException.OtpExpired -> {
                design.setProductState(GetLineProductState.AuthOtpExpired)
            }
            is GetLineAuthException.EmailDomainNotAllowed -> {
                val email = when (val t = retryTarget) {
                    is RetryTarget.EmailSend -> t.email
                    is RetryTarget.EmailVerify -> t.email
                    else -> null
                }
                // Leave OTP step: drop code from RetryTarget / field (same as backFromOtp).
                retryTarget = if (email != null) {
                    RetryTarget.EmailSend(email)
                } else {
                    RetryTarget.Refresh
                }
                design.clearOtpCode()
                design.showEmailEntry(email)
                design.setProductState(GetLineProductState.AuthEmailDomainNotAllowed)
            }
            is GetLineAuthException.NoAccount -> {
                // Message only — no register CTA (recovery None in design).
                design.setProductState(GetLineProductState.AuthNoAccount)
            }
            is GetLineAuthException.RateLimited -> {
                beginResendCooldown()
                design.setProductState(GetLineProductState.AuthRateLimited)
                startResendTicker(design)
            }
            else -> {
                // Unmapped GetLineAuthException → AuthFailed + Send diagnostics (GL-19).
                // Stay on current email/OTP step (authStep unchanged).
                logPreSessionAuthFailed(error)
                design.setProductState(authFailureState())
            }
        }
    }

    private fun beginResendCooldown() {
        resendAvailableAtElapsedMs =
            SystemClock.elapsedRealtime() + RESEND_COOLDOWN_MS
    }

    private fun resendCooldownRemainingSeconds(): Int {
        if (resendAvailableAtElapsedMs <= 0L) return 0
        val leftMs = resendAvailableAtElapsedMs - SystemClock.elapsedRealtime()
        if (leftMs <= 0L) return 0
        return ((leftMs + 999L) / 1000L).toInt()
    }

    private fun startResendTicker(design: GetLineOnboardingDesign) {
        stopResendTicker()
        resendTickerJob = launch {
            while (isActive) {
                val remaining = resendCooldownRemainingSeconds()
                design.setResendCooldown(remaining)
                if (remaining <= 0) break
                delay(1_000L)
            }
        }
    }

    private fun stopResendTicker() {
        resendTickerJob?.cancel()
        resendTickerJob = null
    }

    private fun authFailureState(): GetLineProductState {
        return if (hasValidatedInternetConnection()) {
            GetLineProductState.AuthFailed
        } else {
            GetLineProductState.Offline
        }
    }

    private inner class ProductImportHost : ProductImportFlow.Host {
        override val isForeground: Boolean
            get() = activityStarted

        override suspend fun awaitForeground() {
            awaitActivityStarted()
        }

        override fun takeQueuedReplacement(): GetLineSubscriptionDraft? =
            takeQueuedImport()
    }

    /**
     * Activity side of [BrowserAuthFlow]: the screen-wide busy guard, the retry
     * target shared with the email/import flows, the design surface, and the two
     * calls that need an Activity (browser launch, VPN-less connectivity probe).
     *
     * Design calls are no-ops before [main] installs the design; every flow entry
     * point is reached after that (the deep-link handoff checks explicitly).
     */
    private inner class BrowserAuthHost : BrowserAuthFlow.Host {
        override val isBusy: Boolean
            get() = busy

        override fun setBusy(busy: Boolean) {
            this@GetLineOnboardingActivity.busy = busy
        }

        override fun isOnline(): Boolean = hasValidatedInternetConnection()

        override fun markRetryBrowserLogin(method: AuthMethod) {
            retryTarget = RetryTarget.BrowserLogin(method)
        }

        override fun markRetryRefresh() {
            retryTarget = RetryTarget.Refresh
        }

        override fun markRetryImportPreferred() {
            retryTarget = RetryTarget.ImportPreferredSubscription
        }

        override suspend fun setProductState(state: GetLineProductState) {
            design?.setProductState(state)
        }

        override suspend fun setSessionEstablished(established: Boolean) {
            design?.setSessionEstablished(established)
        }

        override suspend fun showProviders() {
            design?.showProviders()
        }

        override suspend fun refreshEntryState() {
            val design = design ?: return
            this@GetLineOnboardingActivity.refreshEntryState(design)
        }

        override fun setCancelable(cancelable: Boolean) {
            design?.setBrowserAuthCancelable(cancelable)
        }

        override fun authFailureState(): GetLineProductState =
            this@GetLineOnboardingActivity.authFailureState()

        override suspend fun launchBrowser(
            method: AuthMethod,
            authUrl: String,
        ): BrowserAuthLaunchResult = browserAuthLauncher.launch(
            activity = this@GetLineOnboardingActivity,
            authUrl = authUrl,
            redirectMode = AuthTabRedirectMode.NativeScheme,
            rungCeiling = browserRungCeilingFor(method),
        )

        override suspend fun importPreferredSubscription() {
            val design = design ?: return
            this@GetLineOnboardingActivity.importPreferredSubscription(design)
        }

        override suspend fun applyBrowserLoginFailure(method: AuthMethod, error: Exception) {
            val design = design ?: return
            applyLoginFailure(design, RetryTarget.BrowserLogin(method), error)
        }

        override suspend fun applyPostLoginFailure(error: Exception) {
            val design = design ?: return
            applyLoginFailure(design, RetryTarget.ImportPreferredSubscription, error)
        }
    }

    private data class PendingEmailAuth(
        val email: String,
        val otpSent: Boolean,
    )

    private sealed class RetryTarget {
        object Refresh : RetryTarget()
        /** Telegram / Google only — never Email. */
        data class BrowserLogin(val method: AuthMethod) : RetryTarget() {
            init {
                require(method.requiresBrowser()) {
                    "BrowserLogin is only for browser AuthMethod values"
                }
            }
        }
        data class EmailSend(val email: String) : RetryTarget()
        /**
         * [code] is memory-only for retry of the same OTP attempt.
         * Clear on verify success, cancel, or back to email entry.
         * Do not use after verify returns a webToken (OTP may be consumed).
         */
        data class EmailVerify(val email: String, val code: String) : RetryTarget()
        /**
         * Post-OTP (or post-callback) session establishment failed.
         * [webToken] is memory-only — resume device-key/import without re-verify.
         */
        data class CompleteFromWebToken(val webToken: String) : RetryTarget()
        /**
         * Native session exists; the preferred-subscription load or import failed.
         * Retry resumes from the session — no browser auth, no new device key.
         */
        object ImportPreferredSubscription : RetryTarget()
        /**
         * Session exists; subscriptions were empty. User confirmed free-trial
         * activation (or Retry after a failed activation attempt).
         */
        object ActivateTrial : RetryTarget()
        data class ImportSubscription(
            val request: GetLineSubscriptionDraft,
            val subscriptionIdToRemember: String? = null,
        ) : RetryTarget()
    }

    companion object {
        /** Client-side resend spacing; separate from OTP TTL (~300s on server). */
        private const val RESEND_COOLDOWN_MS = 60_000L

        /**
         * Set by [NativeAuthCallbackActivity] after deep-link exchange attempt.
         * [EXTRA_NATIVE_AUTH_SUCCESS] is meaningful only when handled is true.
         */
        const val EXTRA_NATIVE_AUTH_HANDLED = "pro.getline.vpn.extra.native_auth_handled"
        const val EXTRA_NATIVE_AUTH_SUCCESS = "pro.getline.vpn.extra.native_auth_success"

        /**
         * Sign-in opened from Home over a working link-only subscription.
         * Changes entry copy, offers an exit back to Home and suppresses
         * alternate import (QR / manual link) — no auth behaviour.
         */
        const val EXTRA_LINK_ONLY_SIGN_IN =
            "pro.getline.vpn.extra.GET_LINE_LINK_ONLY_SIGN_IN"

        internal const val EXTRA_SESSION_STORAGE_RECOVERED =
            "pro.getline.vpn.extra.GET_LINE_SESSION_STORAGE_RECOVERED"

        /** Cold start routed here because profile backend was unreachable (#98). */
        internal const val EXTRA_BACKEND_UNAVAILABLE =
            "pro.getline.vpn.extra.GET_LINE_BACKEND_UNAVAILABLE"

        private const val EXTRA_IMPORT_TYPE =
            "pro.getline.vpn.extra.GET_LINE_IMPORT_TYPE"
        private const val EXTRA_IMPORT_NAME =
            "pro.getline.vpn.extra.GET_LINE_IMPORT_NAME"
        private const val EXTRA_IMPORT_SOURCE =
            "pro.getline.vpn.extra.GET_LINE_IMPORT_SOURCE"
        private const val EXTRA_IMPORT_INTERVAL =
            "pro.getline.vpn.extra.GET_LINE_IMPORT_INTERVAL"

        fun importIntent(
            context: Context,
            type: GetLineSubscriptionType,
            name: String,
            source: String,
            interval: Long,
        ): Intent {
            return Intent(context, GetLineOnboardingActivity::class.java)
                .putExtra(EXTRA_IMPORT_TYPE, type.name)
                .putExtra(EXTRA_IMPORT_NAME, name)
                .putExtra(EXTRA_IMPORT_SOURCE, source)
                .putExtra(EXTRA_IMPORT_INTERVAL, interval)
        }

        private val Intent.importRequest: GetLineSubscriptionDraft?
            get() {
                val source = getStringExtra(EXTRA_IMPORT_SOURCE) ?: return null
                val type = getStringExtra(EXTRA_IMPORT_TYPE)
                    ?.let {
                        runCatching {
                            GetLineSubscriptionType.valueOf(it)
                        }.getOrNull()
                    }
                    ?: GetLineSubscriptionType.Url
                val name = getStringExtra(EXTRA_IMPORT_NAME) ?: return null

                return GetLineSubscriptionDraft(
                    type = type,
                    name = name,
                    source = source,
                    interval = getLongExtra(EXTRA_IMPORT_INTERVAL, 0L),
                )
            }
    }
}
