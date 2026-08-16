package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.getline.vpn.getlineui.R as GetLineUiR

class SubscriptionHeaderDisplayTest {
    @Test
    fun normalize_trimsAndUppercases() {
        assertEquals("PAID", SubscriptionHeaderDisplay.normalize("  paid "))
        assertEquals("ACTIVE", SubscriptionHeaderDisplay.normalize("Active"))
    }

    @Test
    fun normalize_blankIsNull() {
        assertNull(SubscriptionHeaderDisplay.normalize(null))
        assertNull(SubscriptionHeaderDisplay.normalize(""))
        assertNull(SubscriptionHeaderDisplay.normalize("   "))
    }

    @Test
    fun normalize_rejectsJunk() {
        assertNull(SubscriptionHeaderDisplay.normalize("TOO_LONG_TO_ACCEPT"))
        assertNull(SubscriptionHeaderDisplay.normalize("has-dash"))
        assertNull(SubscriptionHeaderDisplay.normalize("space in"))
    }

    @Test
    fun tariffTitle_mapsKnownAndLeavesUnknown() {
        val titles = mutableListOf<Int>()
        assertEquals(
            "mapped",
            SubscriptionHeaderDisplay.tariffTitle("PAIDPLUS") { id ->
                titles += id
                "mapped"
            },
        )
        assertEquals(listOf(GetLineUiR.string.get_line_tariff_paidplus), titles)
        assertEquals("NEWPLAN", SubscriptionHeaderDisplay.tariffTitle("NEWPLAN") { "unused" })
    }

    @Test
    fun statusText_mapsActiveAndLeavesUnknown() {
        assertEquals(
            "on",
            SubscriptionHeaderDisplay.statusText("ACTIVE") { "on" },
        )
        assertEquals("LIMIT", SubscriptionHeaderDisplay.statusText("LIMIT") { "unused" })
        assertTrue(SubscriptionHeaderDisplay.isActiveStatus("ACTIVE"))
        assertFalse(SubscriptionHeaderDisplay.isActiveStatus("LIMIT"))
        assertFalse(SubscriptionHeaderDisplay.isActiveStatus(null))
    }

    @Test
    fun daysLeft_fromExpire() {
        val day = 86_400_000L
        val now = 1_700_000_000_000L
        assertNull(SubscriptionHeaderDisplay.daysLeft(null, now))
        assertNull(SubscriptionHeaderDisplay.daysLeft(0L, now))
        assertEquals(2, SubscriptionHeaderDisplay.daysLeft(now + 2 * day, now))
        assertEquals(0, SubscriptionHeaderDisplay.daysLeft(now - day, now))
    }
}
