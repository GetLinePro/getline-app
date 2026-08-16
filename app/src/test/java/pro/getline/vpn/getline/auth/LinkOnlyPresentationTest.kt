package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.getline.vpn.getline.GetLineSubscriptionSummary

class LinkOnlyPresentationTest {
    @Test
    fun expireZero_mapsToNull() {
        val p = LinkOnlyPresentation.fromSummary(sample(expire = 0L))
        assertNull(p.expireAtEpochMillis)
    }

    @Test
    fun expirePast_isKept() {
        val past = 1_000_000_000_000L
        val p = LinkOnlyPresentation.fromSummary(sample(expire = past))
        assertEquals(past, p.expireAtEpochMillis)
    }

    @Test
    fun totalZero_limitAndFractionNull() {
        val p = LinkOnlyPresentation.fromSummary(
            sample(upload = 100L, download = 50L, total = 0L),
        )
        assertNull(p.trafficLimitBytes)
        assertEquals(150L, p.trafficUsedBytes)
        assertTrue(p.trafficUnlimited)
    }

    @Test
    fun allTrafficZero_usedUnknown() {
        val p = LinkOnlyPresentation.fromSummary(
            sample(upload = 0L, download = 0L, total = 0L),
        )
        assertNull(p.trafficUsedBytes)
        assertNull(p.trafficLimitBytes)
        // No counters: "unlimited" and "no Subscription-Userinfo" look alike.
        assertFalse(p.trafficUnlimited)
    }

    @Test
    fun totalPositiveUsedZero_usedIsZero() {
        val p = LinkOnlyPresentation.fromSummary(
            sample(upload = 0L, download = 0L, total = 1_000L),
        )
        assertEquals(0L, p.trafficUsedBytes)
        assertEquals(1_000L, p.trafficLimitBytes)
        assertFalse(p.trafficUnlimited)
    }

    @Test
    fun tagAndStatus_passThrough() {
        val p = LinkOnlyPresentation.fromSummary(
            sample(tag = "PAID", status = "Active", deviceLimit = 10),
        )
        assertEquals("PAID", p.tag)
        assertEquals("Active", p.status)
        assertEquals(10, p.deviceLimit)
    }

    @Test
    fun missingTagAndStatus_stayNull() {
        val p = LinkOnlyPresentation.fromSummary(sample())
        assertNull(p.tag)
        assertNull(p.status)
        assertNull(p.deviceLimit)
    }

    private fun sample(
        expire: Long = 1_700_000_000_000L,
        upload: Long = 0L,
        download: Long = 0L,
        total: Long = 0L,
        tag: String? = null,
        status: String? = null,
        deviceLimit: Int? = null,
    ): GetLineSubscriptionSummary {
        return GetLineSubscriptionSummary(
            uuid = "managed-uuid",
            name = "link",
            expire = expire,
            upload = upload,
            download = download,
            total = total,
            tag = tag,
            status = status,
            deviceLimit = deviceLimit,
        )
    }
}
