package pro.getline.vpn

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import pro.getline.vpn.design.GetLineOnboardingDesign
import pro.getline.vpn.design.R
import pro.getline.vpn.design.model.GetLineProductState
import pro.getline.vpn.getline.GetLineBackendProvider
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineImportResult
import pro.getline.vpn.getline.GetLineSubscriptionDraft
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.auth.AuthCallbackParser
import pro.getline.vpn.getline.auth.BrowserAuthLauncher
import pro.getline.vpn.getline.auth.BrowserAuthLaunchResult
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.BrowserAuthStarter
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi
import pro.getline.vpn.util.hasValidatedInternetConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class GetLineOnboardingActivity : BaseActivity<GetLineOnboardingDesign>() {
    private val backend by lazy { GetLineBackendProvider.create(this) }
    private val authApi by lazy { RwpGetLineAuthApi() }
    private val sessionRepository by lazy {
        GetLineSessionRepository(
            api = authApi,
            store = GetLineSessionStore(this),
        )
    }
    private val browserAuthLauncher = BrowserAuthLauncher()
    private val browserAuthStarter by lazy { BrowserAuthStarter(authApi) }
    private val imports = Channel<GetLineSubscriptionDraft>(Channel.UNLIMITED)
    private var busy = false
    private var retryTarget: RetryTarget = RetryTarget.Refresh
    /** ElapsedRealtime when resend becomes allowed again (0 = no cooldown). */
    private var resendAvailableAtElapsedMs: Long = 0L
    private var resendTickerJob: Job? = null
    /**
     * In-progress email login (for restore after accidental subscription import).
     * [otpSent] true after a successful send — resume at OTP, not blank providers.
     */
    private var pendingEmailAuth: PendingEmailAuth? = null

    override suspend fun main() {
        val design = GetLineOnboardingDesign(this)

        setContentDesign(design)
        // Product release: no Advanced door. Debug keeps the button; brand multi-tap
        // remains a quiet hatch. EXTRA_OPEN_ADVANCED route is unchanged.
        design.setAdvancedButtonVisible(BuildConfig.DEBUG)

        val initialImport = intent.importRequest
        if (initialImport != null) {
            importSubscription(design, initialImport)
        } else {
            refreshEntryState(design)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart &&
                        !busy &&
                        retryTarget == RetryTarget.Refresh
                    ) {
                        refreshEntryState(design)
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
                            importSubscription(design)
                        GetLineOnboardingDesign.Request.OpenAdvanced ->
                            openAdvanced()
                        GetLineOnboardingDesign.Request.Retry ->
                            retry(design)
                    }
                }
                imports.onReceive {
                    importSubscription(design, it)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        intent.importRequest?.let(imports::trySend)
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
        } catch (_: Exception) {
            retryTarget = RetryTarget.EmailSend(normalized)
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
            } catch (_: Exception) {
                retryTarget = RetryTarget.EmailVerify(normalizedEmail, normalizedCode)
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
            } catch (_: GetLineAuthException) {
                design.showProviders()
                design.setProductState(authFailureState())
            } catch (_: Exception) {
                design.showProviders()
                design.setProductState(authFailureState())
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
        } catch (_: GetLineAuthException) {
            design.setProductState(authFailureState())
        } catch (_: Exception) {
            design.setProductState(authFailureState())
        } finally {
            busy = false
        }
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
        design.setProductState(GetLineProductState.Loading)

        try {
            val authUrl = browserAuthStarter.resolveAuthUrl(method)
            val launchResult = browserAuthLauncher.launch(this, authUrl)

            val callbackUri = when (launchResult) {
                is BrowserAuthLaunchResult.Completed -> launchResult.callbackUri
                BrowserAuthLaunchResult.Cancelled -> throw GetLineAuthException.Cancelled()
                BrowserAuthLaunchResult.VerificationFailed ->
                    throw GetLineAuthException.VerificationFailed()
                is BrowserAuthLaunchResult.Invalid ->
                    throw GetLineAuthException.InvalidCallback(launchResult.message)
            }

            val webToken = AuthCallbackParser.parse(callbackUri).authToken
            completeLoginFromWebToken(design, webToken)
        } catch (_: GetLineAuthException.Cancelled) {
            retryTarget = RetryTarget.Refresh
            refreshEntryState(design)
        } catch (_: GetLineAuthException.VerificationFailed) {
            retryTarget = RetryTarget.BrowserLogin(method)
            design.setProductState(GetLineProductState.AuthFailed)
        } catch (_: GetLineAuthException.InvalidCallback) {
            retryTarget = RetryTarget.BrowserLogin(method)
            design.setProductState(GetLineProductState.AuthFailed)
        } catch (_: GetLineAuthException) {
            retryTarget = RetryTarget.BrowserLogin(method)
            design.setProductState(authFailureState())
        } catch (_: Exception) {
            retryTarget = RetryTarget.BrowserLogin(method)
            design.setProductState(authFailureState())
        } finally {
            busy = false
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

        // Capture prior selection before loading; loadPreferredSubscription does not
        // overwrite store, so account/subscription changes create a new profile.
        val previousSubscriptionId = sessionRepository.rememberedSubscriptionId()
        val subscription = sessionRepository.loadPreferredSubscription()
        val source = subscription.subscriptionLink
            ?: throw GetLineAuthException.Protocol("Missing subscription link")

        val draft = GetLineSubscriptionDraft(
            type = GetLineSubscriptionType.Url,
            name = subscription.displayName
                ?: getString(R.string.get_line_subscription_profile_name),
            source = source,
        )

        val reuseId = sessionRepository.managedProfileUuid()
            ?.takeIf {
                previousSubscriptionId != null &&
                    previousSubscriptionId == subscription.id
            }
            ?.let { GetLineSubscriptionId(it) }

        importSubscription(
            design = design,
            request = draft,
            reuseId = reuseId,
            alreadyBusy = true,
            subscriptionIdToRemember = subscription.id,
        )
    }

    private suspend fun importSubscription(
        design: GetLineOnboardingDesign,
        request: GetLineSubscriptionDraft? = null,
        reuseId: GetLineSubscriptionId? = null,
        alreadyBusy: Boolean = false,
        subscriptionIdToRemember: String? = null,
    ) {
        if (busy && !alreadyBusy) return

        // User-driven link import (not post-login) can leave email OTP mid-flow.
        val userDrivenImport = !alreadyBusy && subscriptionIdToRemember == null
        val emailToRestore = if (userDrivenImport) pendingEmailAuth else null

        retryTarget = RetryTarget.ImportSubscription(
            request = request,
            reuseId = reuseId,
            subscriptionIdToRemember = subscriptionIdToRemember,
        )
        if (!hasValidatedInternetConnection()) {
            design.setProductState(GetLineProductState.Offline)
            return
        }

        if (!alreadyBusy) {
            busy = true
        }
        design.setProductState(GetLineProductState.Loading)

        try {
            val draft = request ?: GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = getString(R.string.new_profile),
            )

            val created = backend.subscriptions.createOrUpdatePending(draft, reuseId)
            val id = when (created) {
                is GetLineBackendResult.Success -> created.value
                GetLineBackendResult.Unavailable -> {
                    if (emailToRestore != null) {
                        restoreEmailAuth(design)
                    } else {
                        design.setProductState(GetLineProductState.BackendUnavailable)
                    }
                    return
                }
            }

            val result = startActivityForResult(
                ActivityResultContracts.StartActivityForResult(),
                backend.navigation.editSubscription(id),
            )

            when (
                val importResult = backend.navigation.classifyImportResult(
                    result.resultCode,
                    result.data,
                )
            ) {
                is GetLineImportResult.Confirmed -> {
                    pendingEmailAuth = null
                    // Prefer committed Properties URL (manual Add link); fall back to draft.
                    val committedSource = importResult.source
                        ?: request?.source
                    sessionRepository.rememberManagedProfile(
                        uuid = id.value,
                        source = committedSource,
                    )
                    if (subscriptionIdToRemember != null) {
                        sessionRepository.rememberSubscription(subscriptionIdToRemember)
                    }
                    val activateDraft = when {
                        request != null && committedSource == request.source -> request
                        committedSource != null -> GetLineSubscriptionDraft(
                            type = request?.type ?: GetLineSubscriptionType.Url,
                            name = importResult.name
                                ?: request?.name
                                ?: getString(R.string.new_profile),
                            source = committedSource,
                            interval = request?.interval ?: 0L,
                        )
                        else -> request
                    }
                    activateImportedProfile(design, id, activateDraft)
                }
                is GetLineImportResult.Failed -> {
                    if (emailToRestore != null) {
                        // Don't trap the user on import-only UI after a mid-OTP detour.
                        restoreEmailAuth(design)
                    } else {
                        design.setProductState(
                            if (importResult.offline) {
                                GetLineProductState.Offline
                            } else {
                                GetLineProductState.ImportFailed
                            }
                        )
                    }
                }
                GetLineImportResult.Cancelled -> {
                    if (emailToRestore != null) {
                        restoreEmailAuth(design)
                    } else {
                        retryTarget = RetryTarget.Refresh
                        refreshEntryState(design)
                    }
                }
            }
        } finally {
            if (!alreadyBusy) {
                busy = false
            }
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

    private fun openAdvanced() {
        backend.navigation.openAdvanced()
        finish()
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
            is RetryTarget.ImportSubscription ->
                importSubscription(
                    design = design,
                    request = target.request,
                    reuseId = target.reuseId,
                    subscriptionIdToRemember = target.subscriptionIdToRemember,
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

        when (val activated = backend.subscriptions.activateIfImported(id)) {
            is GetLineBackendResult.Success -> {
                if (!activated.value) {
                    retryTarget = RetryTarget.ImportSubscription(request, id)
                    design.setProductState(GetLineProductState.ImportFailed)
                    return
                }

                sessionRepository.rememberManagedProfile(
                    uuid = id.value,
                    source = request?.source,
                )
                startVpnWithPermission()
                backend.navigation.openHome()
                finish()
            }
            GetLineBackendResult.Unavailable -> {
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
                // Stay on current email/OTP step (authStep unchanged).
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

    override fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return false
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
        data class ImportSubscription(
            val request: GetLineSubscriptionDraft?,
            val reuseId: GetLineSubscriptionId? = null,
            val subscriptionIdToRemember: String? = null,
        ) : RetryTarget()
        data class Activate(
            val id: GetLineSubscriptionId,
            val request: GetLineSubscriptionDraft?,
        ) : RetryTarget()
    }

    companion object {
        /** Client-side resend spacing; separate from OTP TTL (~300s on server). */
        private const val RESEND_COOLDOWN_MS = 60_000L

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
