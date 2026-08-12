package pro.getline.vpn.getlineui

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.getline.vpn.getlineui.model.GetLineProductState

class OnboardingExitPolicyTest {
    @Test
    fun standaloneIdleState_closesTheLastActivity() {
        assertEquals(
            OnboardingExitAction.Close,
            actionFor(GetLineProductState.NoSubscription, sessionEstablished = true),
        )
    }

    @Test
    fun linkOnlyIdleState_returnsToTheHomeBelow_regardlessOfSession() {
        assertEquals(
            OnboardingExitAction.NotNow,
            actionFor(GetLineProductState.NoProfile, linkOnlySignIn = true),
        )
        assertEquals(
            OnboardingExitAction.NotNow,
            actionFor(
                GetLineProductState.NoSubscription,
                linkOnlySignIn = true,
                sessionEstablished = true,
            ),
        )
    }

    @Test
    fun emailAndOtpSteps_keepTheirOwnBackAction() {
        assertEquals(
            OnboardingExitAction.None,
            actionFor(
                GetLineProductState.AuthEmailEntry,
                authStep = OnboardingAuthStep.EmailEntry,
            ),
        )
        assertEquals(
            OnboardingExitAction.None,
            actionFor(
                GetLineProductState.AuthInvalidOtp,
                authStep = OnboardingAuthStep.OtpEntry,
            ),
        )
    }

    @Test
    fun offlineEmailStep_onlyKeepsBackWhenLinkOnlyPreSessionChromeIsVisible() {
        assertEquals(
            OnboardingExitAction.Close,
            actionFor(
                GetLineProductState.Offline,
                authStep = OnboardingAuthStep.EmailEntry,
            ),
        )
        assertEquals(
            OnboardingExitAction.None,
            actionFor(
                GetLineProductState.Offline,
                authStep = OnboardingAuthStep.EmailEntry,
                linkOnlySignIn = true,
            ),
        )
        assertEquals(
            OnboardingExitAction.NotNow,
            actionFor(
                GetLineProductState.Offline,
                authStep = OnboardingAuthStep.EmailEntry,
                linkOnlySignIn = true,
                sessionEstablished = true,
            ),
        )
    }

    @Test
    fun loadingHasNoIdleExit() {
        assertEquals(OnboardingExitAction.None, actionFor(GetLineProductState.Loading))
        assertEquals(
            OnboardingExitAction.None,
            actionFor(GetLineProductState.Loading, linkOnlySignIn = true),
        )
    }

    private fun actionFor(
        state: GetLineProductState,
        authStep: OnboardingAuthStep = OnboardingAuthStep.Providers,
        linkOnlySignIn: Boolean = false,
        sessionEstablished: Boolean = false,
    ): OnboardingExitAction = OnboardingExitPolicy.actionFor(
        state = state,
        authStep = authStep,
        linkOnlySignIn = linkOnlySignIn,
        sessionEstablished = sessionEstablished,
    )
}
