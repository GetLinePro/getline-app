package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: empty Google account after successful exchange threw
 * [GetLineAuthException.NoSubscription] with a live session and
 * postLoginImportConsumed=true. Routing that to runPostNativeLoginImport
 * no-ops and left onboarding on Loading instead of the trial CTA.
 */
class BrowserLoginFailurePolicyTest {
    @Test
    fun postSessionFailure_whenSessionAndImportConsumed() {
        assertEquals(
            BrowserLoginFailureDisposition.PostSessionFailure,
            BrowserLoginFailurePolicy.disposition(
                hasSession = true,
                postLoginImportConsumed = true,
            ),
        )
    }

    @Test
    fun siblingSessionImport_whenSessionButImportNotConsumed() {
        assertEquals(
            BrowserLoginFailureDisposition.SiblingSessionImport,
            BrowserLoginFailurePolicy.disposition(
                hasSession = true,
                postLoginImportConsumed = false,
            ),
        )
    }

    @Test
    fun preSessionFailure_whenNoSession_regardlessOfConsumedFlag() {
        assertEquals(
            BrowserLoginFailureDisposition.PreSessionFailure,
            BrowserLoginFailurePolicy.disposition(
                hasSession = false,
                postLoginImportConsumed = false,
            ),
        )
        assertEquals(
            BrowserLoginFailureDisposition.PreSessionFailure,
            BrowserLoginFailurePolicy.disposition(
                hasSession = false,
                postLoginImportConsumed = true,
            ),
        )
    }
}
