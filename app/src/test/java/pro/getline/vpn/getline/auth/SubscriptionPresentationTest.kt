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
    fun managedConfigRefresh_usesAccountActivityOnly() {
        assertTrue(shouldRefreshManagedProfileConfig(sample(isActive = true)))
        assertFalse(shouldRefreshManagedProfileConfig(sample(isActive = false)))
        assertFalse(shouldRefreshManagedProfileConfig(null))
    }

    @Test
    fun missingManagedSnapshot_isTransientOnlyWhenRepairWillRun() {
        assertTrue(
            shouldTreatManagedSnapshotAsTransient(
                localUnavailable = false,
                localMissing = true,
                repairWillRun = true,
            ),
        )
        assertFalse(
            shouldTreatManagedSnapshotAsTransient(
                localUnavailable = false,
                localMissing = true,
                repairWillRun = false,
            ),
        )
        assertTrue(
            shouldTreatManagedSnapshotAsTransient(
                localUnavailable = true,
                localMissing = false,
                repairWillRun = false,
            ),
        )
    }

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
