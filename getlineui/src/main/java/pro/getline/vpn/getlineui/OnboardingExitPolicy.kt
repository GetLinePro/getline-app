package pro.getline.vpn.getlineui

import androidx.annotation.StringRes
import pro.getline.vpn.getlineui.model.GetLineProductState

internal enum class OnboardingAuthStep {
    Providers,
    EmailEntry,
    OtpEntry,
}

internal enum class OnboardingExitAction(@StringRes val label: Int) {
    None(0),
    Close(R.string.get_line_action_close),
    NotNow(R.string.get_line_action_not_now),
}

/** Visible exit for an idle onboarding screen. Loading cancellation is separate. */
internal object OnboardingExitPolicy {
    fun actionFor(
        state: GetLineProductState,
        authStep: OnboardingAuthStep,
        linkOnlySignIn: Boolean,
        sessionEstablished: Boolean,
    ): OnboardingExitAction {
        // New loading states must fail closed until their cancellation semantics
        // are explicitly defined.
        if (state.loading) return OnboardingExitAction.None

        val loginChrome = showsLoginChrome(
            state = state,
            linkOnlySignIn = linkOnlySignIn,
            sessionEstablished = sessionEstablished,
        )
        if (loginChrome && authStep != OnboardingAuthStep.Providers) {
            // Email and OTP steps already show their own explicit Back action.
            return OnboardingExitAction.None
        }

        return when (state) {
            // Content is leaving; Loading cancellation belongs to the browser-race slice.
            GetLineProductState.Content,
            GetLineProductState.Loading,
            // Home-only states: explicitly outside the onboarding exit contract.
            GetLineProductState.PreparingVpn,
            GetLineProductState.ConnectionRepairFailed,
            GetLineProductState.ConnectionRestoreFailed,
            GetLineProductState.SubscriptionExpired -> OnboardingExitAction.None

            GetLineProductState.Offline,
            GetLineProductState.BackendUnavailable,
            GetLineProductState.NoProfile,
            GetLineProductState.ImportFailed,
            GetLineProductState.NoSubscription,
            GetLineProductState.TrialUnavailable,
            GetLineProductState.AuthFailed,
            GetLineProductState.SessionStorageRecovered,
            GetLineProductState.SessionStorageUnavailable,
            GetLineProductState.AuthEmailEntry,
            GetLineProductState.AuthEmailOtpSent,
            GetLineProductState.AuthInvalidOtp,
            GetLineProductState.AuthOtpExpired,
            GetLineProductState.AuthEmailDomainNotAllowed,
            GetLineProductState.AuthNoAccount,
            GetLineProductState.AuthRateLimited -> if (linkOnlySignIn) {
                OnboardingExitAction.NotNow
            } else {
                OnboardingExitAction.Close
            }
        }
    }

    fun showsLoginChrome(
        state: GetLineProductState,
        linkOnlySignIn: Boolean,
        sessionEstablished: Boolean,
    ): Boolean {
        return keepsEmailLoginChrome(state) ||
            (linkOnlySignIn && !sessionEstablished && state == GetLineProductState.Offline)
    }

    /** Keep email/OTP fields visible (do not bounce to an empty provider list). */
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
