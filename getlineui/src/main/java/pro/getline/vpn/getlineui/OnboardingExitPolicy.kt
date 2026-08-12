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
    Cancel(R.string.cancel),
    Close(R.string.get_line_action_close),
    NotNow(R.string.get_line_action_not_now),
}

/** Visible exit for idle screens and explicitly cancellable operation waits. */
internal object OnboardingExitPolicy {
    fun actionFor(
        state: GetLineProductState,
        authStep: OnboardingAuthStep,
        linkOnlySignIn: Boolean,
        sessionEstablished: Boolean,
        browserAuthCancelable: Boolean,
        importWaitCancelable: Boolean,
        importWaitLeavesFlow: Boolean,
    ): OnboardingExitAction {
        if (state.loading) {
            if (state != GetLineProductState.Loading) return OnboardingExitAction.None
            return when {
                browserAuthCancelable -> OnboardingExitAction.Cancel
                importWaitCancelable && importWaitLeavesFlow -> OnboardingExitAction.NotNow
                importWaitCancelable -> OnboardingExitAction.Cancel
                else -> OnboardingExitAction.None
            }
        }

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
            // Content is leaving; cancellable Loading cases returned above.
            GetLineProductState.Content,
            GetLineProductState.Loading,
            // Home-only states: explicitly outside the onboarding exit contract.
            GetLineProductState.PreparingVpn,
            GetLineProductState.ConnectionRepairFailed,
            GetLineProductState.ConnectionRestoreFailed,
            GetLineProductState.SubscriptionExpired -> OnboardingExitAction.None

            // The entry provider screen already has system Back; a second exit CTA
            // above Help only adds noise. Link-only still needs an explicit Home exit.
            GetLineProductState.NoProfile -> if (linkOnlySignIn) {
                OnboardingExitAction.NotNow
            } else {
                OnboardingExitAction.None
            }

            GetLineProductState.Offline,
            GetLineProductState.BackendUnavailable,
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
