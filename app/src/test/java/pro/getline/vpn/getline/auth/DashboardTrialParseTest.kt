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
 * Dashboard parse stays tolerant: missing flags default safely so a partial
 * payload cannot block the post-tap activation path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardTrialParseTest {
    /** Live prod shape, 2026-08-04, first GET after empty subscriptions. */
    @Test
    fun parsesLiveTrialActivationPayload() {
        val json = JSONObject(
            """
            {
              "trial_enabled": true,
              "trial_available": false,
              "trial_auto_activated": true,
              "trial_days": 3,
              "free_plan_enabled": true,
              "free_plan_available": true,
              "plans_count": 6
            }
            """.trimIndent(),
        )

        val info = RwpGetLineAuthApi.parseDashboard(json)

        assertTrue(info.trialEnabled)
        assertFalse(info.trialAvailable)
        assertTrue(info.trialAutoActivated)
        assertEquals(3, info.trialDays)
        assertTrue(info.freePlanEnabled)
        assertTrue(info.freePlanAvailable)
        assertFalse(info.trialPaid)
        assertFalse(info.trialRecurringOnly)
    }

    @Test
    fun missingFieldsDefaultToFalseAndNull() {
        val info = RwpGetLineAuthApi.parseDashboard(JSONObject("{}"))

        assertFalse(info.trialEnabled)
        assertFalse(info.trialAvailable)
        assertFalse(info.trialAutoActivated)
        assertNull(info.trialDays)
        assertFalse(info.freePlanEnabled)
        assertFalse(info.freePlanAvailable)
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
    fun dashboardPathsMatchRwpContract() {
        assertEquals("/api/dashboard", RwpGetLineAuthApi.DASHBOARD_PATH)
        assertEquals("/api/dashboard/trial", RwpGetLineAuthApi.ACTIVATE_TRIAL_PATH)
    }
}
