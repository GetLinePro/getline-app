package pro.getline.vpn.getline.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only an authentication verdict may delete the user's tokens. Everything else
 * keeps the session, because a wrong `true` here signs the user out and takes
 * the remote-repair path (saved subscription URL) with it.
 *
 * Robolectric for `org.json` — the classifier unwraps `{"error":"..."}`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RefreshRejectionClassifierTest {

    @Test
    fun rejected_onlyFor401AndExactInvalidGrant() {
        val rejected = listOf(
            401 to "refresh rejected",
            401 to "",
            401 to null,
            400 to "invalid_grant",
            400 to "  INVALID_GRANT  ",
            400 to """{"error":"invalid_grant"}""",
        )
        for ((code, message) in rejected) {
            assertTrue(
                "expected $code / ${message.orEmpty()} to reject the session",
                isRejectedRefresh(code, message),
            )
        }
    }

    @Test
    fun preserved_whenInvalidGrantIsOnlyPartOfTheMessage() {
        val preserved = listOf(
            // Substring matching used to fire on every one of these.
            400 to "not_invalid_grant",
            400 to "invalid_grant_suspected",
            400 to """{"error":"invalid_grant_expired"}""",
            400 to """{"error":"invalid_request","hint":"invalid_grant recovered"}""",
            400 to "<html><body>invalid_grant</body></html>",
            400 to "previous invalid_grant was recovered",
        )
        for ((code, message) in preserved) {
            assertFalse(
                "expected $code / $message to keep the session",
                isRejectedRefresh(code, message),
            )
        }
    }

    @Test
    fun preserved_forEveryOtherStatus() {
        val preserved = listOf(
            400 to "unknown_error",
            400 to null,
            403 to "invalid_grant",
            429 to "invalid_grant",
            500 to "invalid_grant",
            503 to "invalid_grant",
        )
        for ((code, message) in preserved) {
            assertFalse(
                "expected $code / ${message.orEmpty()} to keep the session",
                isRejectedRefresh(code, message),
            )
        }
    }
}
