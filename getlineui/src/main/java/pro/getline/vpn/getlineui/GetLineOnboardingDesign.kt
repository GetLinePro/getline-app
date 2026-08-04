package pro.getline.vpn.getlineui

import android.content.Context
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pro.getline.vpn.getlineui.databinding.DesignGetLineOnboardingBinding
import pro.getline.vpn.getlineui.dialog.requestModelTextInput
import pro.getline.vpn.getlineui.model.GetLineImportStage
import pro.getline.vpn.getlineui.model.GetLineProductState
import pro.getline.vpn.getlineui.model.GetLineRecoveryAction
import pro.getline.vpn.getlineui.util.Validator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class GetLineOnboardingDesign(context: Context) :
    GetLineScreen<GetLineOnboardingDesign.Request>(context) {
    sealed class Request {
        object LoginTelegram : Request()
        object LoginGoogle : Request()
        object LoginEmail : Request()
        data class SendEmailOtp(val email: String) : Request()
        data class VerifyEmailOtp(val email: String, val code: String) : Request()
        /** OTP → email entry; clears code. */
        object BackFromOtp : Request()
        /** Email entry → provider list. */
        object BackFromEmail : Request()
        object AddExistingSubscription : Request()
        /** Leave sign-in without signing in (link-only entry only). */
        object Dismiss : Request()
        /** Product QR import (same pipeline as [AddExistingSubscription]). */
        object ScanQrCode : Request()
        /**
         * Diagnostic hatch into MainActivity advanced shell.
         * Not a release product surface — debug button or brand multi-tap only.
         */
        object OpenAdvanced : Request()
        /** Open existing HelpActivity (support links, about). */
        object OpenHelp : Request()
        object Retry : Request()
        /** Explicit free-trial activation after empty subscriptions. */
        object ActivateTrial : Request()
        /** Open the account portal (plans / billing) in the browser. */
        object OpenAccountPortal : Request()
        /** GL-19: local safe diagnostic report → preview → share. */
        object SendDiagnostics : Request()
    }

    private enum class AuthStep {
        Providers,
        EmailEntry,
        OtpEntry,
    }

    private val binding = DesignGetLineOnboardingBinding
        .inflate(layoutInflater, contentRoot, false)

    private var authStep: AuthStep = AuthStep.Providers
    private var pendingEmail: String = ""
    private var resendSecondsRemaining: Int = 0
    /**
     * Entered from Home over a working link-only profile, not from a cold start
     * (see Activity EXTRA_LINK_ONLY_SIGN_IN). Suppresses alternate import —
     * the subscription is already present.
     */
    private var linkOnlySignIn: Boolean = false
    /** Session persisted; only the subscription step is left (see [setSessionEstablished]). */
    private var sessionEstablished: Boolean = false

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.brandTitle.text = brandLockup()
        // OTP must not enter hierarchy SavedState (rotation / process death).
        binding.otpField.isSaveEnabled = false
        binding.resendEnabled = true
        binding.resendLabel = context.getString(R.string.get_line_email_resend)
        binding.stateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.Retry)
                GetLineRecoveryAction.ImportSubscription ->
                    request(Request.AddExistingSubscription)
                GetLineRecoveryAction.ActivateTrial -> request(Request.ActivateTrial)
                GetLineRecoveryAction.OpenAccountPortal ->
                    request(Request.OpenAccountPortal)
                // Prefer Google: reachable without VPN; Telegram often is not.
                // SubscriptionExpired uses OpenAccount → this path (not the portal).
                GetLineRecoveryAction.OpenAccount,
                GetLineRecoveryAction.SignIn -> request(Request.LoginGoogle)
                GetLineRecoveryAction.OpenProfiles,
                GetLineRecoveryAction.None -> Unit
            }
        }
        binding.stateView.setOnSendDiagnostics {
            request(Request.SendDiagnostics)
        }
        applyState(GetLineProductState.Loading)
    }

    suspend fun setProductState(state: GetLineProductState) {
        withContext(Dispatchers.Main) {
            applyState(state)
        }
    }

    /**
     * Best-effort import progress: swap Loading explanation for the stage label.
     * No-op when not currently showing Loading.
     */
    suspend fun setImportStage(stage: GetLineImportStage) {
        withContext(Dispatchers.Main) {
            if (lastProductState != GetLineProductState.Loading) return@withContext
            binding.stateView.renderMessage(
                title = GetLineProductState.Loading.title,
                explanation = stage.label,
                loading = true,
            )
        }
    }

    enum class MismatchChoice { UseAccount, KeepLinkOnly }

    /**
     * Link-only profile vs account preferred subscription mismatch.
     * Not cancelable: both branches lead to a consistent state; deferral would
     * leave a live session with a foreign active subscription.
     */
    suspend fun confirmAccountMismatch(): MismatchChoice {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.get_line_account_mismatch_title)
                    .setMessage(R.string.get_line_account_mismatch_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.get_line_account_mismatch_use_account) { _, _ ->
                        if (!cont.isCompleted) {
                            cont.resume(MismatchChoice.UseAccount)
                        }
                    }
                    .setNegativeButton(R.string.get_line_account_mismatch_keep_link) { _, _ ->
                        if (!cont.isCompleted) {
                            cont.resume(MismatchChoice.KeepLinkOnly)
                        }
                    }
                    .setOnDismissListener {
                        // OEM quirks / unexpected dismiss must not hang the coroutine.
                        // Default to KeepLinkOnly (Decision 1: no mixed session state).
                        if (!cont.isCompleted) {
                            cont.resume(MismatchChoice.KeepLinkOnly)
                        }
                    }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /**
     * Product URL dialog for "add existing subscription".
     * [validator] is supplied by the host (control-plane host policy lives in
     * `:app`; `:getlineui` must not depend on it).
     * @return validated URL, or null when the user cancels.
     */
    suspend fun requestSubscriptionUrl(validator: Validator): String? {
        return withContext(Dispatchers.Main) {
            // Validator may accept after internal trim (host policy); return the
            // same normalized form the import pipeline will fetch.
            context.requestModelTextInput(
                initial = null,
                title = context.getText(R.string.url),
                reset = null,
                hint = context.getText(R.string.profile_url),
                error = context.getText(R.string.get_line_import_link_rejected),
                validator = validator,
            )?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Confirm QR import. Shows [host] only — full URL carries a subscription
     * token and must not appear in a dialog the user might screenshot/share.
     */
    suspend fun confirmSubscriptionImport(host: String): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.get_line_qr_confirm_title)
                    .setMessage(
                        context.getString(R.string.get_line_qr_confirm_message, host),
                    )
                    .setCancelable(true)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (!cont.isCompleted) cont.resume(true)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        if (!cont.isCompleted) cont.resume(false)
                    }
                    .setOnDismissListener {
                        if (!cont.isCompleted) cont.resume(false)
                    }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /** Scan failed: offer the manual path instead of leaving a dead end. */
    suspend fun offerManualEntryAfterScanFailure(@StringRes message: Int): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setMessage(message)
                    .setCancelable(true)
                    .setPositiveButton(R.string.get_line_action_enter_link_manually) { _, _ ->
                        if (!cont.isCompleted) cont.resume(true)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        if (!cont.isCompleted) cont.resume(false)
                    }
                    .setOnDismissListener {
                        if (!cont.isCompleted) cont.resume(false)
                    }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /**
     * Resend cooldown UI on OTP step.
     * [secondsRemaining] 0 → enabled "Resend code"; >0 → disabled countdown label.
     */
    suspend fun setResendCooldown(secondsRemaining: Int) {
        withContext(Dispatchers.Main) {
            resendSecondsRemaining = secondsRemaining.coerceAtLeast(0)
            applyResendBinding()
        }
    }

    /** Provider list (Google / Email / Telegram). Clears OTP input. */
    suspend fun showProviders() {
        withContext(Dispatchers.Main) {
            authStep = AuthStep.Providers
            pendingEmail = ""
            clearOtpField()
            applyAuthStepVisibility(lastProductState)
        }
    }

    /** Email entry step. Keeps typed email when returning from OTP. */
    suspend fun showEmailEntry(email: String? = null) {
        withContext(Dispatchers.Main) {
            authStep = AuthStep.EmailEntry
            if (email != null) {
                pendingEmail = email
                binding.emailField.setText(email)
            }
            clearOtpField()
            applyAuthStepVisibility(lastProductState)
        }
    }

    /**
     * OTP entry for [email]. Code stays in the field only (memory); never serialized.
     * [clearCode] true when first entering the step or leaving it later via [clearOtpField].
     */
    suspend fun showOtpEntry(email: String, clearCode: Boolean = true) {
        withContext(Dispatchers.Main) {
            authStep = AuthStep.OtpEntry
            pendingEmail = email
            if (binding.emailField.text?.toString() != email) {
                binding.emailField.setText(email)
            }
            if (clearCode) {
                clearOtpField()
            }
            applyAuthStepVisibility(lastProductState)
            applyResendBinding()
        }
    }

    /** Drop OTP digits from the field (back / success / cancel). */
    suspend fun clearOtpCode() {
        withContext(Dispatchers.Main) {
            clearOtpField()
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    fun onLoginTelegram() {
        request(Request.LoginTelegram)
    }

    fun onLoginGoogle() {
        request(Request.LoginGoogle)
    }

    fun onLoginEmail() {
        request(Request.LoginEmail)
    }

    fun onScanQr() {
        request(Request.ScanQrCode)
    }

    fun onEnterLink() {
        request(Request.AddExistingSubscription)
    }

    fun onSendEmailOtp() {
        val email = binding.emailField.text?.toString()?.trim().orEmpty()
        if (email.isEmpty()) return
        pendingEmail = email
        request(Request.SendEmailOtp(email))
    }

    fun onResendEmailOtp() {
        if (resendSecondsRemaining > 0) return
        val email = pendingEmail.ifBlank {
            binding.emailField.text?.toString()?.trim().orEmpty()
        }
        if (email.isEmpty()) return
        request(Request.SendEmailOtp(email))
    }

    fun onVerifyEmailOtp() {
        val email = pendingEmail.ifBlank {
            binding.emailField.text?.toString()?.trim().orEmpty()
        }
        val code = binding.otpField.text?.toString()?.trim().orEmpty()
        if (email.isEmpty() || code.isEmpty()) return
        request(Request.VerifyEmailOtp(email, code))
    }

    fun onBackEmailAuth() {
        tryNavigateEmailAuthBack()
    }

    /**
     * Email vertical back: OTP → email → providers.
     * @return true if a step was handled (do not finish activity).
     */
    fun tryNavigateEmailAuthBack(): Boolean {
        // Error states hide the email chrome while [authStep] stays where it was.
        // Stepping back through invisible screens only swallows Back presses —
        // on the link-only entry it swallows the way home.
        if (!showsLoginChrome(lastProductState)) return false
        return when (authStep) {
            AuthStep.OtpEntry -> {
                request(Request.BackFromOtp)
                true
            }
            AuthStep.EmailEntry -> {
                request(Request.BackFromEmail)
                true
            }
            AuthStep.Providers -> false
        }
    }

    fun onOpenAdvanced() {
        request(Request.OpenAdvanced)
    }

    fun onOpenHelp() {
        request(Request.OpenHelp)
    }

    /**
     * Sign-in entered from Home while a link-only subscription keeps working.
     * Changes the entry copy, drops the "add a subscription link" recovery CTA
     * (the user already has one) and offers an explicit way back to Home.
     */
    fun setLinkOnlySignIn(enabled: Boolean) {
        if (linkOnlySignIn == enabled) return
        linkOnlySignIn = enabled
        applyState(lastProductState)
    }

    fun onDismiss() {
        request(Request.Dismiss)
    }

    /**
     * A native session already exists (post-login subscription step in flight).
     * From here login controls must stay hidden even on states that would
     * otherwise keep them: a tap would re-run the browser leg (new device key) or
     * send another OTP instead of retrying the subscription step.
     */
    fun setSessionEstablished(established: Boolean) {
        if (sessionEstablished == established) return
        sessionEstablished = established
        applyState(lastProductState)
    }

    /**
     * Show/hide the explicit Advanced button.
     * Release product navigation keeps this gone; debug builds may show it.
     * Multi-tap brand hatch remains available regardless.
     */
    fun setAdvancedButtonVisible(visible: Boolean) {
        binding.openAdvanced.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Quiet diagnostic hatch: N taps on the brand title within a short window.
     * Opens Advanced without advertising it as a product control.
     */
    fun onBrandTitleClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - brandTapWindowStartMs > BRAND_TAP_WINDOW_MS) {
            brandTapCount = 0
            brandTapWindowStartMs = now
        }
        brandTapCount += 1
        if (brandTapCount >= BRAND_TAP_THRESHOLD) {
            brandTapCount = 0
            brandTapWindowStartMs = 0L
            request(Request.OpenAdvanced)
        }
    }

    /**
     * The entry-screen title as the brand lockup: accent "Get", plain "Line",
     * soft accent "Pro" — the same split as ic_getline_wordmark, drawn as text
     * so it stays one line at any font scale.
     *
     * The string resource remains the only place the name is spelled; a part
     * that is not found there is simply left in the default colour rather than
     * coloured at the wrong offsets.
     */
    private fun brandLockup(): CharSequence {
        val name = context.getString(R.string.get_line_brand_name)
        val lockup = SpannableString(name)
        lockup.tint(name, "Get", R.color.getline_brand_wordmark_accent)
        lockup.tint(name, "Pro", R.color.getline_brand_wordmark_suffix)
        return lockup
    }

    private fun SpannableString.tint(source: String, part: String, colorRes: Int) {
        val start = source.indexOf(part)
        if (start < 0) return
        setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, colorRes)),
            start,
            start + part.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
    }

    private var brandTapCount: Int = 0
    private var brandTapWindowStartMs: Long = 0L

    private var lastProductState: GetLineProductState = GetLineProductState.Loading

    companion object {
        private const val BRAND_TAP_THRESHOLD = 7
        private const val BRAND_TAP_WINDOW_MS = 3_000L
    }

    private fun applyState(state: GetLineProductState) {
        lastProductState = state
        val action = recoveryActionFor(state)
        val secondary = secondaryRecoveryActionFor(state)
        if (linkOnlySignIn && state == GetLineProductState.NoProfile) {
            // Nothing is missing here: the link-only profile is imported and routing
            // traffic. NoProfile copy ("no VPN profile yet… add a subscription link")
            // would read as if the working subscription had been lost.
            binding.stateView.renderMessage(
                title = R.string.get_line_link_only_sign_in_title,
                explanation = R.string.get_line_link_only_sign_in_explanation,
                action = action,
                secondaryAction = secondary,
                showSendDiagnostics = offersDiagnostics(state),
            )
        } else {
            binding.stateView.render(
                state = state,
                action = action,
                secondaryAction = secondary,
                showSendDiagnostics = offersDiagnostics(state),
            )
        }
        binding.actionsEnabled = state != GetLineProductState.Loading
        // Idle auth + errors only — no Help during Loading/PreparingVpn or on Content exit.
        binding.helpVisible = !state.loading && state != GetLineProductState.Content
        applyAuthStepVisibility(state)
        applyResendBinding()
    }

    /** Diagnostics next to Retry where the tester already is (GL-19). */
    private fun offersDiagnostics(state: GetLineProductState): Boolean {
        return state == GetLineProductState.ImportFailed ||
            state == GetLineProductState.AuthFailed
    }

    /**
     * Link/QR import lives in the alternate-import block, not state-view recovery.
     * Error states keep Retry; Home still uses [GetLineRecoveryAction.ImportSubscription].
     */
    private fun recoveryActionFor(state: GetLineProductState): GetLineRecoveryAction {
        return when (state) {
            GetLineProductState.Offline,
            GetLineProductState.BackendUnavailable,
            GetLineProductState.ImportFailed,
            GetLineProductState.AuthFailed,
            GetLineProductState.SessionStorageUnavailable -> GetLineRecoveryAction.Retry
            GetLineProductState.SessionStorageRecovered,
            GetLineProductState.NoProfile -> GetLineRecoveryAction.None
            GetLineProductState.NoSubscription -> GetLineRecoveryAction.ActivateTrial
            GetLineProductState.TrialUnavailable -> GetLineRecoveryAction.OpenAccountPortal
            // Prefer Google (reachable without VPN); portal is Subscription-tab only on Home.
            GetLineProductState.SubscriptionExpired -> GetLineRecoveryAction.OpenAccount
            // Home-only repair; onboarding never shows these states.
            GetLineProductState.ConnectionRepairFailed,
            GetLineProductState.ConnectionRestoreFailed,
            GetLineProductState.PreparingVpn -> GetLineRecoveryAction.None
            GetLineProductState.AuthEmailEntry,
            GetLineProductState.AuthEmailOtpSent,
            GetLineProductState.AuthInvalidOtp,
            GetLineProductState.AuthOtpExpired,
            GetLineProductState.AuthEmailDomainNotAllowed,
            GetLineProductState.AuthNoAccount,
            GetLineProductState.AuthRateLimited -> GetLineRecoveryAction.None
            GetLineProductState.Content,
            GetLineProductState.Loading -> GetLineRecoveryAction.None
        }
    }

    private fun secondaryRecoveryActionFor(state: GetLineProductState): GetLineRecoveryAction {
        return when (state) {
            GetLineProductState.NoSubscription -> GetLineRecoveryAction.OpenAccountPortal
            else -> GetLineRecoveryAction.None
        }
    }

    private fun applyAuthStepVisibility(state: GetLineProductState) {
        val loginChrome = showsLoginChrome(state)
        binding.providersVisible = loginChrome && authStep == AuthStep.Providers
        binding.emailStepVisible = loginChrome && authStep == AuthStep.EmailEntry
        binding.otpStepVisible = loginChrome && authStep == AuthStep.OtpEntry
        // Offline/ImportFailed hide login chrome (including mid-email/OTP). Alternate
        // import must stay; pure authStep==Providers would hide it after email→offline.
        binding.alternateImportVisible =
            !linkOnlySignIn &&
                showsAlternateImport(state) &&
                (authStep == AuthStep.Providers || !loginChrome)
        // Hidden only while an email/OTP step is on screen — that step has its own
        // "Back". Where the chrome is hidden (post-session errors) this is the only
        // way out, and an exit racing an in-flight import (loading) is a trap.
        binding.dismissVisible = linkOnlySignIn &&
            (authStep == AuthStep.Providers || !loginChrome) &&
            !state.loading &&
            state != GetLineProductState.Content
    }

    /** States where QR + manual link must stay reachable. */
    private fun showsAlternateImport(state: GetLineProductState): Boolean {
        return when (state) {
            GetLineProductState.NoProfile,
            GetLineProductState.Offline,
            GetLineProductState.AuthFailed,
            GetLineProductState.SessionStorageRecovered,
            GetLineProductState.ImportFailed,
            GetLineProductState.NoSubscription,
            GetLineProductState.TrialUnavailable,
            GetLineProductState.BackendUnavailable -> true
            else -> false
        }
    }

    /**
     * Login controls actually on screen for [state].
     *
     * Offline normally replaces the whole entry screen — there is nothing to do
     * without network. Coming from a working link-only VPN it is only a warning
     * above the buttons, so the provider list stays reachable instead of leaving a
     * lone Retry on a screen the user opened deliberately. Once a session exists
     * that no longer holds: the failing step is the subscription import, and
     * re-running login there mints a new device key or burns another OTP.
     */
    private fun showsLoginChrome(state: GetLineProductState): Boolean {
        return keepsEmailLoginChrome(state) ||
            (linkOnlySignIn && !sessionEstablished && state == GetLineProductState.Offline)
    }

    private fun applyResendBinding() {
        val remaining = resendSecondsRemaining
        binding.resendEnabled = remaining <= 0
        binding.resendLabel = if (remaining > 0) {
            context.getString(R.string.get_line_email_resend_in, remaining)
        } else {
            context.getString(R.string.get_line_email_resend)
        }
    }

    private fun clearOtpField() {
        binding.otpField.text = null
    }

    /** Keep email/OTP fields visible (do not bounce to empty provider list). */
    private fun keepsEmailLoginChrome(state: GetLineProductState): Boolean {
        return when (state) {
            GetLineProductState.NoProfile,
            GetLineProductState.AuthEmailEntry,
            GetLineProductState.AuthEmailOtpSent,
            GetLineProductState.AuthFailed,
            GetLineProductState.SessionStorageRecovered,
            GetLineProductState.AuthInvalidOtp,
            GetLineProductState.AuthOtpExpired,
            GetLineProductState.AuthEmailDomainNotAllowed,
            GetLineProductState.AuthNoAccount,
            GetLineProductState.AuthRateLimited -> true
            else -> false
        }
    }
}
