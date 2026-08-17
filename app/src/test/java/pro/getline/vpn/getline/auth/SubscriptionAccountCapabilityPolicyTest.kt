package pro.getline.vpn.getline.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAccountCapabilityPolicyTest {
    @Test
    fun accountBoundSession_canSignOut_withoutManagedSignInRoute() {
        assertTrue(
            SubscriptionAccountCapabilityPolicy.canSafelySignOut(
                hasSession = true,
                needsPostLoginStep = false,
            ),
        )
        assertFalse(
            SubscriptionAccountCapabilityPolicy.shouldResumeManagedSubscriptionSignIn(
                hasSession = true,
                hasManagedBinding = true,
                needsPostLoginStep = false,
            ),
        )
    }

    @Test
    fun managedBindingWithoutSession_usesSignInRoute_andCannotSignOut() {
        assertTrue(
            SubscriptionAccountCapabilityPolicy.shouldResumeManagedSubscriptionSignIn(
                hasSession = false,
                hasManagedBinding = true,
                needsPostLoginStep = false,
            ),
        )
        assertFalse(
            SubscriptionAccountCapabilityPolicy.canSafelySignOut(
                hasSession = false,
                needsPostLoginStep = false,
            ),
        )
    }

    @Test
    fun mixedPostLogin_usesSignInRoute_andCannotSignOut() {
        assertTrue(
            SubscriptionAccountCapabilityPolicy.shouldResumeManagedSubscriptionSignIn(
                hasSession = true,
                hasManagedBinding = true,
                needsPostLoginStep = true,
            ),
        )
        assertFalse(
            SubscriptionAccountCapabilityPolicy.canSafelySignOut(
                hasSession = true,
                needsPostLoginStep = true,
            ),
        )
    }
}
