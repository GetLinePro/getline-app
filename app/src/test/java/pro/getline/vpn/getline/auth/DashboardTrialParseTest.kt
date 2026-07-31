package pro.getline.vpn.getline.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GET /api/dashboard is called for its side effect — it provisions the trial —
 * so parsing must never be the reason a login fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardTrialParseTest {
    /** Live prod shape, 2026-07-31, fresh account right after register. */
    @Test
    fun parsesLiveTrialActivationPayload() {
        val json = JSONObject(
            """
            {
              "trial_enabled": true,
              "trial_available": false,
              "trial_auto_activated": true,
              "trial_days": 3,
              "free_plan_enabled": false,
              "plans_count": 6
            }
            """.trimIndent(),
        )

        val info = RwpGetLineAuthApi.parseDashboard(json)

        assertTrue(info.trialEnabled)
        assertFalse(info.trialAvailable)
        assertTrue(info.trialAutoActivated)
        assertEquals(3, info.trialDays)
    }

    @Test
    fun missingFieldsDefaultToFalseAndNull() {
        val info = RwpGetLineAuthApi.parseDashboard(JSONObject("{}"))

        assertFalse(info.trialEnabled)
        assertFalse(info.trialAvailable)
        assertFalse(info.trialAutoActivated)
        assertNull(info.trialDays)
    }

    @Test
    fun nullAndZeroTrialDaysBecomeNull() {
        val explicitNull = RwpGetLineAuthApi.parseDashboard(
            JSONObject("""{"trial_days": null}"""),
        )
        val zero = RwpGetLineAuthApi.parseDashboard(
            JSONObject("""{"trial_days": 0}"""),
        )

        assertNull(explicitNull.trialDays)
        assertNull(zero.trialDays)
    }

    @Test
    fun dashboardPathMatchesRwpContract() {
        assertEquals("/api/dashboard", RwpGetLineAuthApi.DASHBOARD_PATH)
    }
}
