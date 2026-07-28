package pro.getline.vpn.design

import android.content.Context
import android.os.SystemClock
import android.view.View
import pro.getline.vpn.design.databinding.DesignGetLineOnboardingBinding
import pro.getline.vpn.design.model.GetLineProductState
import pro.getline.vpn.design.model.GetLineRecoveryAction
import pro.getline.vpn.design.util.layoutInflater
import pro.getline.vpn.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GetLineOnboardingDesign(context: Context) :
    Design<GetLineOnboardingDesign.Request>(context) {
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
        /**
         * Diagnostic hatch into MainActivity advanced shell.
         * Not a release product surface — debug button or brand multi-tap only.
         */
        object OpenAdvanced : Request()
        object Retry : Request()
    }

    private enum class AuthStep {
        Providers,
        EmailEntry,
        OtpEntry,
    }

    private val binding = DesignGetLineOnboardingBinding
        .inflate(context.layoutInflater, context.root, false)

    private var authStep: AuthStep = AuthStep.Providers
    private var pendingEmail: String = ""
    private var resendSecondsRemaining: Int = 0

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        // OTP must not enter hierarchy SavedState (rotation / process death).
        binding.otpField.isSaveEnabled = false
        binding.resendEnabled = true
        binding.resendLabel = context.getString(R.string.get_line_email_resend)
        binding.stateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.Retry)
                GetLineRecoveryAction.ImportSubscription ->
                    request(Request.AddExistingSubscription)
                // Prefer Google: reachable without VPN; Telegram often is not.
                GetLineRecoveryAction.OpenAccount -> request(Request.LoginGoogle)
                GetLineRecoveryAction.SignIn -> request(Request.LoginGoogle)
                GetLineRecoveryAction.OpenProfiles,
                GetLineRecoveryAction.None -> Unit
            }
        }
        applyState(GetLineProductState.Loading)
    }

    suspend fun setProductState(state: GetLineProductState) {
        withContext(Dispatchers.Main) {
            applyState(state)
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
        binding.stateView.render(state, action)
        binding.actionsEnabled = state != GetLineProductState.Loading
        applyAuthStepVisibility(state)
        applyResendBinding()
    }

    /**
     * Import-subscription CTA only on the provider list. Email/OTP steps must not
     * offer it (easy mis-tap; traps users out of OTP entry).
     */
    private fun recoveryActionFor(state: GetLineProductState): GetLineRecoveryAction {
        return when (state) {
            GetLineProductState.Offline,
            GetLineProductState.BackendUnavailable,
            GetLineProductState.ImportFailed,
            GetLineProductState.AuthFailed -> GetLineRecoveryAction.Retry
            GetLineProductState.NoProfile ->
                if (authStep == AuthStep.Providers) {
                    GetLineRecoveryAction.ImportSubscription
                } else {
                    GetLineRecoveryAction.None
                }
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
            GetLineProductState.SubscriptionExpired -> GetLineRecoveryAction.OpenAccount
            GetLineProductState.Content,
            GetLineProductState.Loading -> GetLineRecoveryAction.None
        }
    }

    private fun applyAuthStepVisibility(state: GetLineProductState) {
        val loginChrome = keepsEmailLoginChrome(state)
        binding.providersVisible = loginChrome && authStep == AuthStep.Providers
        binding.emailStepVisible = loginChrome && authStep == AuthStep.EmailEntry
        binding.otpStepVisible = loginChrome && authStep == AuthStep.OtpEntry
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
            GetLineProductState.AuthInvalidOtp,
            GetLineProductState.AuthOtpExpired,
            GetLineProductState.AuthEmailDomainNotAllowed,
            GetLineProductState.AuthNoAccount,
            GetLineProductState.AuthRateLimited -> true
            else -> false
        }
    }
}
