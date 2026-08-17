package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPresentationTest {
    @Test
    fun selectPreferred_primaryWithLinkWins() {
        val primary = sample(id = "1", primary = true, link = "https://a")
        val other = sample(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(other, primary))
        assertEquals("1", response.selectPreferred()?.id)
    }

    @Test
    fun fromLocalSummary_mapsTagStatusAndDeviceLimit() {
        val summary = pro.getline.vpn.getline.GetLineSubscriptionSummary(
            uuid = "managed",
            name = "link",
            expire = 1_700_172_800_000L,
            upload = 1L,
            download = 2L,
            total = 10L,
            tag = "paid",
            status = "Active",
            deviceLimit = 10,
        )
        val p = SubscriptionPresentation.fromLocalSummary(
            summary = summary,
            string = { id ->
                if (id == pro.getline.vpn.getlineui.R.string.get_line_tariff_paid) {
                    "Standard"
                } else {
                    "Active"
                }
            },
            nowMillis = 1_700_000_000_000L,
        )
        assertEquals("managed", p.id)
        assertEquals("Standard", p.title)
        assertTrue(p.isActive)
        assertEquals("Active", p.statusText)
        assertTrue(p.showStatus)
        assertEquals(10, p.deviceLimit)
        assertEquals(3L, p.trafficUsedBytes)
        assertEquals(10L, p.trafficLimitBytes)
        assertFalse(p.trafficUnlimited)
        assertEquals(2, p.daysLeft)
    }

    @Test
    fun fromLocalSummary_countedTrafficWithNoAllowance_isUnlimited() {
        val unlimited = SubscriptionPresentation.fromLocalSummary(
            summary = pro.getline.vpn.getline.GetLineSubscriptionSummary(
                uuid = "u",
                name = "n",
                expire = 0L,
                upload = 100L,
                download = 50L,
                total = 0L,
                tag = "UNLIM",
                status = "ACTIVE",
            ),
            string = { "unused" },
        )
        assertTrue(unlimited.trafficUnlimited)
        assertNull(unlimited.trafficLimitBytes)

        // Same row before the first byte is counted: still "unknown", not unlimited.
        val untouched = SubscriptionPresentation.fromLocalSummary(
            summary = pro.getline.vpn.getline.GetLineSubscriptionSummary(
                uuid = "u",
                name = "n",
                expire = 0L,
                upload = 0L,
                download = 0L,
                total = 0L,
                tag = "UNLIM",
                status = "ACTIVE",
            ),
            string = { "unused" },
        )
        assertFalse(untouched.trafficUnlimited)
    }

    @Test
    fun fromLocalSummary_unknownTagShownAsCode_invalidHidden() {
        val unknown = SubscriptionPresentation.fromLocalSummary(
            summary = pro.getline.vpn.getline.GetLineSubscriptionSummary(
                uuid = "u",
                name = "n",
                expire = 0L,
                upload = 0L,
                download = 0L,
                total = 0L,
                tag = "NEWPLAN",
                status = "LIMIT",
            ),
            string = { "unused" },
        )
        assertEquals("NEWPLAN", unknown.title)
        assertEquals("LIMIT", unknown.statusText)
        assertTrue(unknown.showStatus)
        assertFalse(unknown.isActive)

        val invalid = SubscriptionPresentation.fromLocalSummary(
            summary = pro.getline.vpn.getline.GetLineSubscriptionSummary(
                uuid = "u",
                name = "n",
                expire = 0L,
                upload = 0L,
                download = 0L,
                total = 0L,
                tag = "nope!",
                status = null,
            ),
            string = { "unused" },
        )
        assertNull(invalid.title)
        assertFalse(invalid.showStatus)
        assertNull(invalid.statusText)
    }

    @Test
    fun fromLocalSummary_expireBoundaries() {
        assertNull(
            SubscriptionPresentation.fromLocalSummary(summary(expire = 0L), { "unused" })
                .expireAtEpochMillis,
        )
        val past = 1_000_000_000_000L
        assertEquals(
            past,
            SubscriptionPresentation.fromLocalSummary(summary(expire = past), { "unused" })
                .expireAtEpochMillis,
        )
    }

    @Test
    fun fromLocalSummary_trafficHeuristics() {
        val counted = SubscriptionPresentation.fromLocalSummary(
            summary(upload = 100L, download = 50L, total = 0L),
            { "unused" },
        )
        assertEquals(150L, counted.trafficUsedBytes)
        assertNull(counted.trafficLimitBytes)
        assertTrue(counted.trafficUnlimited)

        // No counters at all: "unlimited" and "no Subscription-Userinfo" look alike.
        val silent = SubscriptionPresentation.fromLocalSummary(summary(), { "unused" })
        assertNull(silent.trafficUsedBytes)
        assertNull(silent.trafficLimitBytes)
        assertFalse(silent.trafficUnlimited)
        assertNull(silent.deviceLimit)

        val allowance = SubscriptionPresentation.fromLocalSummary(
            summary(total = 1_000L),
            { "unused" },
        )
        assertEquals(0L, allowance.trafficUsedBytes)
        assertEquals(1_000L, allowance.trafficLimitBytes)
        assertFalse(allowance.trafficUnlimited)
    }

    @Test
    fun selectPreferred_fallbackWhenPrimaryHasNoLink() {
        val primaryNoLink = sample(id = "1", primary = true, link = null)
        val secondary = sample(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(primaryNoLink, secondary))
        assertEquals("2", response.selectPreferred()?.id)
    }

    private fun summary(
        expire: Long = 1_700_000_000_000L,
        upload: Long = 0L,
        download: Long = 0L,
        total: Long = 0L,
        tag: String? = null,
        status: String? = null,
        deviceLimit: Int? = null,
    ) = pro.getline.vpn.getline.GetLineSubscriptionSummary(
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

    private fun sample(
        id: String = "1",
        planName: String? = "Trial",
        name: String? = "sub",
        primary: Boolean = true,
        isActive: Boolean = true,
        planArchived: Boolean = false,
        totalDeviceLimit: Int? = 3,
        deviceLimit: Int? = 3,
        devicesCount: Int? = -1,
        link: String? = "https://example.test/sub",
        traffic: SubscriptionTraffic? = SubscriptionTraffic(1L, 2L, 0.0, false),
    ): SubscriptionItem {
        return SubscriptionItem(
            id = id,
            name = name,
            planName = planName,
            planType = null,
            kind = "trial",
            isPrimary = primary,
            isActive = isActive,
            expireAtEpochMillis = 1_700_000_000_000L,
            daysLeft = 2,
            deviceLimit = deviceLimit,
            totalDeviceLimit = totalDeviceLimit,
            devicesCount = devicesCount,
            traffic = traffic,
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = planArchived,
            subscriptionLink = link,
        )
    }
}
