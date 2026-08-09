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
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineImportCoordinator
import pro.getline.vpn.getline.GetLineSubscriptionDraft
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.activateImportedProfileWhenForeground
import pro.getline.vpn.getline.runPendingManagedProfileCleanup
import pro.getline.vpn.getline.auth.AuthCallbackParser
import pro.getline.vpn.getline.auth.AuthCallbackResult
import pro.getline.vpn.getline.auth.AuthTabRedirectMode
import pro.getline.vpn.getline.auth.BrowserAuthLauncher
import pro.getline.vpn.getline.auth.BrowserAuthLaunchResult
import pro.getline.vpn.getline.auth.browserRungCeilingFor
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.diagnostics.DiagnosticReportShare
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.GetLineSessionStorageException
import pro.getline.vpn.getline.auth.LinkOnlyBindingPolicy
import pro.getline.vpn.getline.auth.NativeAuthPkce
import pro.getline.vpn.getline.auth.PendingNativeAuth
import pro.getline.vpn.getline.auth.PendingNativeAuthStore
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi
import pro.getline.vpn.getline.auth.SubscriptionLinkMatcher
import java.util.UUID
import pro.getline.vpn.getline.accountportal.AccountPortalLaunchResult
import pro.getline.vpn.getline.accountportal.AccountPortalUriPolicy
import pro.getline.vpn.getline.accountportal.DefaultAccountPortalLauncher
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.util.hasValidatedInternetConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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
    private val browserAuthLauncher = BrowserAuthLauncher()
    private val pendingNativeAuthStore by lazy { PendingNativeAuthStore(this) }
    private val accountPortalLauncher = DefaultAccountPortalLauncher()
    private val imports = Channel<GetLineSubscriptionDraft>(Channel.UNLIMITED)
    private var busy = false
    /** True after Custom Tab / external browser launch until deep-link or UI leave. */
    private var awaitingNativeDeepLink = false
    /**
     * Deep-link handoff found an established session while browser-auth still
     * held [busy] (Auth Tab + package VIEW race). Run import once busy clears.
     */
    private var deferredNativeImport = false
    /** Prefer one preferred-subscription import per browser-auth attempt. */
    private var postLoginImportConsumed = false
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
        val design = GetLineOnboardingDesign(this)

        setContentDesign(design)
        // Read once: a later external import (onNewIntent) replaces the Activity
        // intent: this screen must not silently lose its exit affordance, and
        // QR / manual import stays hidden for the whole session of this entry.
        linkOnlySignIn = intent.getBooleanExtra(EXTRA_LINK_ONLY_SIGN_IN, false)
        design.setLinkOnlySignIn(linkOnlySignIn)
        // Resumed post-login step (mismatch dialog / import) starts with a live
        // session — login controls must stay hidden on its error states too.
        val initialHasSession = try {
            sessionRepository.hasSession()
        } catch (_: GetLineSessionStorageException) {
            waitForSecureSessionStorage(design)
            return
        }
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
            // Explicit external / deep-link import replaces any stale pending work.
            initialImport != null -> importSubscription(design, initialImport)
            // Resume durable import after HOME / process death. Prefer pending over
            // the incomplete-login fork: UseAccount mid-flight is still link-only
            // until Success writes subscriptionId.
            sessionRepository.hasPendingImport() -> {
                val draft = sessionRepository.pendingImportDraft()
                when {
                    draft == null -> {
                        sessionRepository.clearPendingImport()
                        refreshEntryState(design)
                    }
                    // Offline resume dead-ends on Offline + Retry. With a managed
                    // profile the VPN is still usable, so go Home instead of
                    // trapping it here; pending stays durable for the next launch.
                    !hasValidatedInternetConnection() &&
                        !sessionRepository.managedProfileUuid().isNullOrBlank() -> {
                        backend.navigation.openHome()
                        finish()
                    }
                    else -> importSubscription(
                        design = design,
                        request = draft,
                        reuseId = sessionRepository.pendingImportReuseId(),
                        subscriptionIdToRemember =
                            sessionRepository.pendingImportSubscriptionIdToRemember(),
                        previousManagedUuidToDelete =
                            sessionRepository.pendingImportPreviousManagedUuidToDelete(),
                    )
                }
            }
            // Session + link-only binding: mismatch dialog / import never finished.
            // Do not leave the user on providers with a live mixed state.
            sessionRepository.needsPostLoginSubscriptionStep() ->
                resumePreferredSubscription(design)
            else -> refreshEntryState(design)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when {
                        it != Event.ActivityStart || busy -> Unit
                        // Custom Tab / external: resume is not cancel. Intermediate
                        // onStart while the tab is still open, and ActivityStart vs
                        // onNewIntent handoff, both fire here — clearing pending was
                        // a false-cancel.
                        //
                        // Invariant: no session without an in-app start (pending only
                        // exists after put()). Abandon window is PendingNativeAuth TTL
                        // (~10 min), not "until first resume" — intentional so a late
                        // deep-link after UI leave can still complete.
                        awaitingNativeDeepLink -> {
                            awaitingNativeDeepLink = false
                            if (sessionRepository.hasSession()) {
                                if (!postLoginImportConsumed) {
                                    runPostNativeLoginImport(design)
                                }
                            } else {
                                // Unblock UI; pending stays until TTL / take / new put.
                                design.showProviders()
                                design.setProductState(entryProductState())
                            }
                        }
                        // Re-join only while coordinator still running (HOME without destroy).
                        // Failed imports keep pending for Retry / next cold start, not every onStart.
                        GetLineImportCoordinator.isInFlight() -> {
                            val target = retryTarget as? RetryTarget.ImportSubscription
                            val draft = target?.request
                                ?: sessionRepository.pendingImportDraft()
                            if (draft != null) {
                                importSubscription(
                                    design = design,
                                    request = draft,
                                    reuseId = target?.reuseId
                                        ?: sessionRepository.pendingImportReuseId(),
                                    subscriptionIdToRemember = target?.subscriptionIdToRemember
                                        ?: sessionRepository.pendingImportSubscriptionIdToRemember(),
                                    previousManagedUuidToDelete =
                                        target?.previousManagedUuidToDelete
                                            ?: sessionRepository
                                                .pendingImportPreviousManagedUuidToDelete(),
                                )
                            }
                        }
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
                            signInWithBrowserProvider(design, AuthMethod.Telegram)
                        GetLineOnboardingDesign.Request.LoginGoogle ->
                            signInWithBrowserProvider(design, AuthMethod.Google)
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
                        // Home is still below (openLinkOnlySignIn does not finish it);
                        // finishing here returns to the running VPN.
                        GetLineOnboardingDesign.Request.Dismiss ->
                            if (!busy) {
                                finish()
                            }
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
     * External import while another is in flight: do not drop B. The active
     * waiter finishes (or is superseded by coordinator) and [drainAndContinueImport]
     * picks B up before opening Home.
     */
    private suspend fun enqueueOrStartImport(
        design: GetLineOnboardingDesign,
        request: GetLineSubscriptionDraft,
    ) {
        if (busy) {
            pendingExternalImport = request
            // Cancel headless A so B is the sole in-flight import; A's onTerminal
            // is invalidated via generation.
            GetLineImportCoordinator.reset()
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
        val design = design ?: return
        awaitingNativeDeepLink = false
        val success = intent.getBooleanExtra(EXTRA_NATIVE_AUTH_SUCCESS, false)
        // One-shot: config change / recreate must not re-run import or paint failure.
        clearNativeAuthExtras(intent)

        if (sessionRepository.hasSession()) {
            // Includes: success handoff, or failure handoff after sibling Auth Tab won.
            if (busy) {
                if (!postLoginImportConsumed) {
                    deferredNativeImport = true
                }
                return
            }
            runPostNativeLoginImport(design)
            return
        }
        if (success) {
            Log.w("native_auth_handoff success_flag_without_session")
        }
        retryTarget = RetryTarget.Refresh
        design.showProviders()
        design.setProductState(authFailureState())
    }

    private fun clearNativeAuthExtras(intent: Intent) {
        intent.removeExtra(EXTRA_NATIVE_AUTH_HANDLED)
        intent.removeExtra(EXTRA_NATIVE_AUTH_SUCCESS)
        // Keep Activity intent in sync when extras arrived via setIntent(onNewIntent).
        if (intent === this.intent) return
        this.intent?.removeExtra(EXTRA_NATIVE_AUTH_HANDLED)
        this.intent?.removeExtra(EXTRA_NATIVE_AUTH_SUCCESS)
    }

    private suspend fun runPostNativeLoginImport(design: GetLineOnboardingDesign) {
        if (postLoginImportConsumed) return
        postLoginImportConsumed = true
        deferredNativeImport = false
        val acquiredBusy = !busy
        if (acquiredBusy) busy = true
        design.setProductState(GetLineProductState.Loading)
        try {
            retryTarget = RetryTarget.ImportPreferredSubscription
            design.setSessionEstablished(true)
            importPreferredSubscription(design)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetLineAuthException) {
            applyLoginFailure(design, RetryTarget.ImportPreferredSubscription, e)
        } catch (e: Exception) {
            applyLoginFailure(design, RetryTarget.ImportPreferredSubscription, e)
        } finally {
            if (acquiredBusy) busy = false
        }
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

    private suspend fun signInWithBrowserProvider(
        design: GetLineOnboardingDesign,
        method: AuthMethod,
    ) {
        if (busy) return
        require(method.requiresBrowser()) {
            "AuthMethod.$method does not use browser auth"
        }

        retryTarget = RetryTarget.BrowserLogin(method)
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        busy = true
        awaitingNativeDeepLink = false
        deferredNativeImport = false
        postLoginImportConsumed = false
        design.setProductState(GetLineProductState.Loading)

        try {
            when (method) {
                AuthMethod.Google,
                AuthMethod.Telegram,
                -> signInNativePkce(design, method)
                AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
            }
        } catch (_: GetLineAuthException.Cancelled) {
            if (sessionRepository.hasSession()) {
                // Package VIEW completed while Auth Tab reported cancel.
                runPostNativeLoginImport(design)
            } else {
                // Abandon attempt — drop pending (not an exchange re-put case).
                pendingNativeAuthStore.clear()
                retryTarget = RetryTarget.Refresh
                refreshEntryState(design)
            }
        } catch (_: GetLineAuthException.VerificationFailed) {
            if (sessionRepository.hasSession()) {
                runPostNativeLoginImport(design)
            } else {
                pendingNativeAuthStore.clear()
                retryTarget = RetryTarget.BrowserLogin(method)
                design.setProductState(GetLineProductState.AuthFailed)
            }
        } catch (_: GetLineAuthException.InvalidCallback) {
            if (sessionRepository.hasSession()) {
                runPostNativeLoginImport(design)
            } else {
                // Missing/foreign callback — not a re-put after take.
                pendingNativeAuthStore.clear()
                Log.w("browser_auth_invalid_callback method=${method.name}")
                retryTarget = RetryTarget.BrowserLogin(method)
                design.setProductState(GetLineProductState.AuthFailed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetLineAuthException) {
            if (sessionRepository.hasSession()) {
                runPostNativeLoginImport(design)
            } else {
                // Exchange/network failure: completeLoginFrom* may have re-put pending.
                // Do not clear — Retry within TTL can still use dual delivery / re-tap.
                applyLoginFailure(design, RetryTarget.BrowserLogin(method), e)
            }
        } catch (e: Exception) {
            if (sessionRepository.hasSession()) {
                runPostNativeLoginImport(design)
            } else {
                applyLoginFailure(design, RetryTarget.BrowserLogin(method), e)
            }
        } finally {
            busy = false
            if (deferredNativeImport &&
                !postLoginImportConsumed &&
                sessionRepository.hasSession()
            ) {
                runPostNativeLoginImport(design)
            } else {
                deferredNativeImport = false
            }
        }
    }

    /**
     * Browser provider: app-owned PKCE + native package callback.
     * Caller holds [busy]; does not clear it.
     */
    private suspend fun signInNativePkce(
        design: GetLineOnboardingDesign,
        method: AuthMethod,
    ) {
        val pkce = NativeAuthPkce.generate()
        val callbackUri = AppEnvironment.nativeCallbackUri
        pendingNativeAuthStore.put(
            PendingNativeAuth(
                provider = method.name,
                verifier = pkce.verifier,
                callbackUri = callbackUri,
                createdAtMs = System.currentTimeMillis(),
                correlationId = UUID.randomUUID().toString(),
            ),
        )

        val start = authApi.startBrowserAuth(
            method = method,
            codeChallenge = pkce.challenge,
            appRedirect = callbackUri,
        )
        Log.i("browser_auth_launch method=${method.name}")
        val launchResult = browserAuthLauncher.launch(
            activity = this,
            authUrl = start.authUrl,
            redirectMode = AuthTabRedirectMode.NativeScheme,
            rungCeiling = browserRungCeilingFor(method),
        )
        handleBrowserLaunchResult(
            design = design,
            method = method,
            launchResult = launchResult,
            nativeCallbackUri = callbackUri,
        )
    }

    private suspend fun handleBrowserLaunchResult(
        design: GetLineOnboardingDesign,
        method: AuthMethod,
        launchResult: BrowserAuthLaunchResult,
        nativeCallbackUri: String?,
    ) {
        when (launchResult) {
            is BrowserAuthLaunchResult.Completed -> {
                when (val parsed = AuthCallbackParser.parse(launchResult.callbackUri)) {
                    is AuthCallbackResult.NativeCode -> {
                        val pendingUri = nativeCallbackUri
                            ?: AppEnvironment.nativeCallbackUri
                        val pending = pendingNativeAuthStore.takeIfMatches(
                            callbackUri = pendingUri,
                            provider = method.name,
                        )
                        if (pending != null) {
                            completeLoginFromNativeCode(design, parsed.code, pending)
                        } else if (sessionRepository.hasSession()) {
                            // Package VIEW already established via NativeAuthCallbackActivity.
                            runPostNativeLoginImport(design)
                        } else {
                            throw GetLineAuthException.InvalidCallback("No matching pending auth")
                        }
                    }
                    is AuthCallbackResult.WebToken -> {
                        val gateUri = nativeCallbackUri
                            ?: AppEnvironment.nativeCallbackUri
                        val pending = pendingNativeAuthStore.takeIfMatches(
                            callbackUri = gateUri,
                            provider = AuthMethod.Telegram.name,
                        )
                        if (pending != null) {
                            completeLoginFromWebTokenRestoringPending(
                                design,
                                parsed.authToken,
                                pending,
                            )
                        } else if (sessionRepository.hasSession()) {
                            runPostNativeLoginImport(design)
                        } else {
                            throw GetLineAuthException.InvalidCallback("No matching pending auth")
                        }
                    }
                }
            }
            BrowserAuthLaunchResult.AwaitingDeepLink -> {
                // Custom Tab / external: NativeAuthCallbackActivity.
                awaitingNativeDeepLink = true
            }
            BrowserAuthLaunchResult.Cancelled -> {
                if (sessionRepository.hasSession()) {
                    runPostNativeLoginImport(design)
                } else {
                    pendingNativeAuthStore.clear()
                    throw GetLineAuthException.Cancelled()
                }
            }
            BrowserAuthLaunchResult.VerificationFailed -> {
                if (sessionRepository.hasSession()) {
                    runPostNativeLoginImport(design)
                } else {
                    pendingNativeAuthStore.clear()
                    throw GetLineAuthException.VerificationFailed()
                }
            }
            BrowserAuthLaunchResult.NoBrowser -> {
                pendingNativeAuthStore.clear()
                Log.w("browser_auth_no_browser method=${method.name}")
                retryTarget = RetryTarget.BrowserLogin(method)
                design.setProductState(GetLineProductState.AuthFailed)
            }
            is BrowserAuthLaunchResult.Invalid -> {
                if (sessionRepository.hasSession()) {
                    runPostNativeLoginImport(design)
                } else {
                    pendingNativeAuthStore.clear()
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
     * Caller must hold [busy] = true; this method does not clear it.
     */
    private suspend fun completeLoginFromNativeCode(
        design: GetLineOnboardingDesign,
        code: String,
        pending: PendingNativeAuth,
    ) {
        try {
            sessionRepository.establishFromNativeCode(code, pending.verifier)
        } catch (e: Exception) {
            pendingNativeAuthStore.put(pending)
            throw e
        }
        postLoginImportConsumed = true
        deferredNativeImport = false
        retryTarget = RetryTarget.ImportPreferredSubscription
        design.setSessionEstablished(true)
        importPreferredSubscription(design)
    }

    private suspend fun completeLoginFromWebTokenRestoringPending(
        design: GetLineOnboardingDesign,
        webToken: String,
        pending: PendingNativeAuth,
    ) {
        try {
            sessionRepository.establishFromWebToken(webToken)
        } catch (e: Exception) {
            pendingNativeAuthStore.put(pending)
            throw e
        }
        postLoginImportConsumed = true
        deferredNativeImport = false
        retryTarget = RetryTarget.ImportPreferredSubscription
        design.setSessionEstablished(true)
        importPreferredSubscription(design)
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
        val load = sessionRepository.loadPreferredSubscriptionWithList()
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
        val previousSubscriptionId = sessionRepository.rememberedSubscriptionId()
        val managedUuid = sessionRepository.managedProfileUuid()
        val managedSource = sessionRepository.managedProfileSource()
        val all = load.all

        val linkOnly = LinkOnlyBindingPolicy.isLinkOnlyBinding(
            managedUuid = managedUuid,
            managedSource = managedSource,
            rememberedSubscriptionId = previousSubscriptionId,
        )
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

        var previousManagedUuidToDelete: String? = null
        if (linkOnly && !linkMatch) {
            when (design.confirmAccountMismatch()) {
                GetLineOnboardingDesign.MismatchChoice.KeepLinkOnly -> {
                    sessionRepository.discardSessionKeepingSubscription()
                    backend.navigation.openHome()
                    finish()
                    return
                }
                GetLineOnboardingDesign.MismatchChoice.UseAccount -> {
                    previousManagedUuidToDelete = managedUuid
                }
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

        val reuseId = managedUuid
            ?.takeIf {
                (previousSubscriptionId != null &&
                    previousSubscriptionId == subscription.id) ||
                    linkMatch
            }
            ?.let { GetLineSubscriptionId(it) }

        importSubscription(
            design = design,
            request = draft,
            reuseId = reuseId,
            alreadyBusy = true,
            subscriptionIdToRemember = subscription.id,
            previousManagedUuidToDelete = previousManagedUuidToDelete,
        )
    }

    private suspend fun importSubscription(
        design: GetLineOnboardingDesign,
        request: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId? = null,
        alreadyBusy: Boolean = false,
        subscriptionIdToRemember: String? = null,
        previousManagedUuidToDelete: String? = null,
    ) {
        if (busy && !alreadyBusy) {
            // Nested call while waiting: treat as supersede target.
            pendingExternalImport = request
            GetLineImportCoordinator.reset()
            return
        }

        // User-driven link import (not post-login) can leave email OTP mid-flow.
        val userDrivenImport = !alreadyBusy && subscriptionIdToRemember == null
        val emailToRestore = if (userDrivenImport) pendingEmailAuth else null

        retryTarget = RetryTarget.ImportSubscription(
            request = request,
            reuseId = reuseId,
            subscriptionIdToRemember = subscriptionIdToRemember,
            previousManagedUuidToDelete = previousManagedUuidToDelete,
        )
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        if (!alreadyBusy) {
            busy = true
        }
        design.setProductState(GetLineProductState.Loading)

        // Durable request so HOME/relaunch resumes instead of dead-ending.
        sessionRepository.savePendingImport(
            draft = request,
            reuseId = reuseId,
            subscriptionIdToRemember = subscriptionIdToRemember,
            previousManagedUuidToDelete = previousManagedUuidToDelete,
        )
        val importKey = GetLineImportCoordinator.importKey(
            source = request.source,
            subscriptionIdToRemember = subscriptionIdToRemember,
            reuseId = reuseId,
        )

        try {
            // Process-scoped import: Activity destroy cancels this waiter only,
            // not the fetch (until supersede/reset). Terminal binding in onTerminal
            // is generation-gated against logout.
            val terminal = GetLineImportCoordinator.run(
                request = GetLineImportCoordinator.ImportRequest(
                    key = importKey,
                    draft = request,
                    reuseId = reuseId,
                ),
                import = { onProgress ->
                    backend.subscriptions.importAndCommit(request, reuseId, onProgress)
                },
                onProgress = { stage ->
                    runCatching { design.setImportStage(stage) }
                },
                onTerminal = { result ->
                    when (result) {
                        is GetLineImportCoordinator.ImportTerminal.Success -> {
                            val previousUuid = previousManagedUuidToDelete
                            if (previousUuid != null && previousUuid != result.id.value) {
                                // Move cleanup ownership out of pending import before
                                // that transaction is cleared. A process death from
                                // here onward leaves a repairable tombstone.
                                sessionRepository.rememberPendingProfileCleanup(previousUuid)
                            }
                            sessionRepository.rememberManagedProfile(
                                uuid = result.id.value,
                                source = request.source,
                            )
                            if (subscriptionIdToRemember != null) {
                                sessionRepository.rememberSubscription(subscriptionIdToRemember)
                            }
                            sessionRepository.clearPendingImport()
                            // Cleanup is not part of import success. Unavailable keeps
                            // the tombstone for Home repair; Deleted/NotFound consume it.
                            sessionRepository.pendingProfileCleanupUuids().forEach { pending ->
                                runPendingManagedProfileCleanup(
                                    pendingUuid = pending,
                                    managedUuid = result.id.value,
                                    canDelete = true,
                                    // The immediately replaced binding may still
                                    // own the running tunnel; older tombstones cannot.
                                    stopBeforeDelete = pending == previousUuid,
                                    stopVpn = backend.vpn::stop,
                                    deleteManaged = backend.subscriptions::deleteManaged,
                                    clearPending =
                                        sessionRepository::clearPendingProfileCleanup,
                                )
                            }
                            // No profile UUID: lands in user-shareable diagnostics (GL-19).
                            Log.i(
                                "import_terminal success " +
                                    "verdict=${sessionRepository.consistencyVerdict()}",
                            )
                        }
                        is GetLineImportCoordinator.ImportTerminal.Unavailable -> {
                            sessionRepository.clearPendingImport()
                            // reason is a safe discriminator (kind=/code=), never raw t.message.
                            Log.w(
                                "import_terminal unavailable " +
                                    (result.reason ?: "kind=unknown"),
                            )
                        }
                    }
                },
            )

            // Prefer a newer external import over Home / fail UI.
            if (drainAndContinueImport(design)) {
                return
            }

            when (terminal) {
                is GetLineImportCoordinator.ImportTerminal.Success -> {
                    pendingEmailAuth = null
                    // A live waiter owns activation and its recoverable UI. If the
                    // waiter was destroyed, the durable managed binding routes the
                    // next launch to Home, whose repair activates the profile.
                    activateImportedProfile(design, terminal.id, request)
                }
                is GetLineImportCoordinator.ImportTerminal.Unavailable -> {
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
                GetLineImportCoordinator.ImportTerminal.Superseded -> {
                    // Replacement should have been drained above. A reset without
                    // a replacement (logout) leaves this Activity until navigation.
                    design.setProductState(GetLineProductState.Loading)
                }
            }
        } catch (e: CancellationException) {
            // UI gone (HOME / renav). Import may still be running; pending kept only
            // while in-flight — terminal paths clear it.
            // No importKey: it starts with the subscription URL.
            Log.i("import_waiter_cancelled in_flight=${GetLineImportCoordinator.isInFlight()}")
            throw e
        } finally {
            if (!alreadyBusy) {
                busy = false
            }
        }
    }

    /**
     * Drain channel + [pendingExternalImport]. Starts the newest draft if any.
     * @return true if a replacement import was started (caller must not open Home).
     */
    private suspend fun drainAndContinueImport(design: GetLineOnboardingDesign): Boolean {
        // Keep the no-draft path non-suspending: finishImportToHome calls this
        // immediately after awaitActivityStarted to close the foreground/navigation race.
        var latest: GetLineSubscriptionDraft? = pendingExternalImport
        pendingExternalImport = null
        while (true) {
            val next = imports.tryReceive().getOrNull() ?: break
            latest = next
        }
        val draft = latest ?: return false
        // Supersede must not drop UseAccount orphan cleanup: savePendingImport in
        // the nested call would otherwise clear previousManagedUuidToDelete.
        val previousToDelete =
            (retryTarget as? RetryTarget.ImportSubscription)?.previousManagedUuidToDelete
                ?: sessionRepository.pendingImportPreviousManagedUuidToDelete()
        // Keep outer busy ownership; nested call must not clear it early.
        importSubscription(
            design = design,
            request = draft,
            alreadyBusy = true,
            previousManagedUuidToDelete = previousToDelete,
        )
        return true
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
                signInWithBrowserProvider(design, target.method)
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
                    reuseId = target.reuseId,
                    subscriptionIdToRemember = target.subscriptionIdToRemember,
                    previousManagedUuidToDelete = target.previousManagedUuidToDelete,
                )
            is RetryTarget.Activate ->
                activateImportedProfile(design, target.id, target.request)
        }
    }

    override fun handleBackPressed() {
        if (busy) return
        val design = design
        if (design != null && design.tryNavigateEmailAuthBack()) {
            return
        }
        super.handleBackPressed()
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

    private suspend fun activateImportedProfile(
        design: GetLineOnboardingDesign,
        id: GetLineSubscriptionId,
        request: GetLineSubscriptionDraft?,
    ) {
        retryTarget = RetryTarget.Activate(id, request)
        design.setProductState(GetLineProductState.Loading)

        val activated = activateImportedProfileWhenForeground(
            isForeground = { activityStarted },
            awaitForeground = ::awaitActivityStarted,
            activate = { backend.subscriptions.activateIfImported(id) },
        )

        when (activated) {
            is GetLineBackendResult.Success -> {
                if (!activated.value) {
                    // request is non-null on all product import paths; reuse id on retry.
                    if (request != null) {
                        retryTarget = RetryTarget.ImportSubscription(
                            request = request,
                            reuseId = id,
                        )
                    }
                    // GL-19: ImportFailed without a prior import_terminal line is opaque.
                    Log.w("import_terminal activate_failed")
                    design.setProductState(GetLineProductState.ImportFailed)
                    return
                }

                finishImportToHome(design)
            }
            GetLineBackendResult.Unavailable -> {
                Log.w("import_terminal activate_unavailable")
                design.setProductState(GetLineProductState.BackendUnavailable)
            }
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
            val reuseId: GetLineSubscriptionId? = null,
            val subscriptionIdToRemember: String? = null,
            /** Link-only profile replaced by account preferred; delete after Success. */
            val previousManagedUuidToDelete: String? = null,
        ) : RetryTarget()
        data class Activate(
            val id: GetLineSubscriptionId,
            val request: GetLineSubscriptionDraft?,
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
