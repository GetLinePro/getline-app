package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSubscriptionConsistencyTest {
    @Test
    fun classify_sessionWinsEvenWithBinding() {
        assertEquals(
            SessionSubscriptionConsistency.Verdict.SessionOk,
            SessionSubscriptionConsistency.classify(
                hasSession = true,
                hasManagedBinding = true,
            ),
        )
    }

    @Test
    fun classify_sessionWithoutBindingYet() {
        assertEquals(
            SessionSubscriptionConsistency.Verdict.SessionOk,
            SessionSubscriptionConsistency.classify(
                hasSession = true,
                hasManagedBinding = false,
            ),
        )
    }

    @Test
    fun classify_bindingWithoutSession_isManualImportShape() {
        assertEquals(
            SessionSubscriptionConsistency.Verdict.BindingWithoutSession,
            SessionSubscriptionConsistency.classify(
                hasSession = false,
                hasManagedBinding = true,
            ),
        )
    }

    @Test
    fun classify_emptyDevice() {
        assertEquals(
            SessionSubscriptionConsistency.Verdict.Empty,
            SessionSubscriptionConsistency.classify(
                hasSession = false,
                hasManagedBinding = false,
            ),
        )
    }
}
