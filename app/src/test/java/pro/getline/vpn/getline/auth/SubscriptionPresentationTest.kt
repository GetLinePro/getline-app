package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPresentationTest {
    @Test
    fun primaryPreferred_isMapped() {
        val item = sample(
            id = "42",
            planName = "Trial",
            name = "sub-name",
            isActive = true,
            totalDeviceLimit = 3,
            deviceLimit = 1,
            devicesCount = -1,
        )
        val p = SubscriptionPresentation.fromPreferred(item, fallbackTitle = "Subscription")
        assertEquals("42", p.id)
        assertEquals("Trial", p.title)
        assertTrue(p.isActive)
        assertEquals(3, p.deviceLimit)
    }

    @Test
    fun displayName_usesPlanNameThenName() {
        val withPlan = sample(planName = "Pro", name = "legacy")
        assertEquals(
            "Pro",
            SubscriptionPresentation.fromPreferred(withPlan, "Subscription").title,
        )
        val nameOnly = sample(planName = null, name = "legacy")
        assertEquals(
            "legacy",
            SubscriptionPresentation.fromPreferred(nameOnly, "Subscription").title,
        )
        val neither = sample(planName = null, name = null)
        assertEquals(
            "Subscription",
            SubscriptionPresentation.fromPreferred(neither, "Subscription").title,
        )
    }

    @Test
    fun deviceLimit_usesTotalThenDeviceLimit() {
        val total = sample(totalDeviceLimit = 5, deviceLimit = 2)
        assertEquals(
            5,
            SubscriptionPresentation.fromPreferred(total, "X").deviceLimit,
        )
        val onlyDevice = sample(totalDeviceLimit = null, deviceLimit = 2)
        assertEquals(
            2,
            SubscriptionPresentation.fromPreferred(onlyDevice, "X").deviceLimit,
        )
    }

    @Test
    fun devicesCountNegative_neverBecomesDeviceLimit() {
        val item = sample(
            totalDeviceLimit = null,
            deviceLimit = null,
            devicesCount = -1,
        )
        val p = SubscriptionPresentation.fromPreferred(item, "X")
        assertNull(p.deviceLimit)
    }

    @Test
    fun devicesCountPositive_stillNotShownAsCount() {
        // Spec: do not show actual device count; only limit when present.
        val item = sample(
            totalDeviceLimit = 3,
            deviceLimit = 3,
            devicesCount = 2,
        )
        val p = SubscriptionPresentation.fromPreferred(item, "X")
        assertEquals(3, p.deviceLimit)
        // presentation has no devicesCount field — count is dropped at map boundary
    }

    @Test
    fun unlimitedTraffic_flagMapped() {
        val item = sample(
            traffic = SubscriptionTraffic(0L, 0L, 0.0, isUnlimited = true),
        )
        val p = SubscriptionPresentation.fromPreferred(item, "X")
        assertTrue(p.trafficUnlimited)
    }

    @Test
    fun inactiveSubscription_isActiveFalse() {
        val item = sample(isActive = false, planArchived = true)
        val p = SubscriptionPresentation.fromPreferred(item, "X")
        assertFalse(p.isActive)
        // plan_archived must not force inactive in mapping; is_active is source of truth
        assertTrue(item.planArchived)
    }

    @Test
    fun selectPreferred_primaryWithLinkWins() {
        val primary = sample(id = "1", primary = true, link = "https://a")
        val other = sample(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(other, primary))
        assertEquals("1", response.selectPreferred()?.id)
    }

    @Test
    fun selectPreferred_fallbackWhenPrimaryHasNoLink() {
        val primaryNoLink = sample(id = "1", primary = true, link = null)
        val secondary = sample(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(primaryNoLink, secondary))
        assertEquals("2", response.selectPreferred()?.id)
        val presentation = SubscriptionPresentation.fromPreferred(
            response.selectPreferred()!!,
            "Subscription",
        )
        assertEquals("2", presentation.id)
    }

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
