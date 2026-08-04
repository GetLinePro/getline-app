package pro.getline.vpn.getline.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionsJsonTest {
    @Test
    fun parseResponse_mapsTrialSubscriptionFields() {
        val json = JSONObject(
            """
            {
              "autopay_available": true,
              "subscriptions": [
                {
                  "id": 42,
                  "name": "sub-name",
                  "plan_name": "Trial",
                  "plan_type": "trial",
                  "kind": "trial",
                  "is_primary": true,
                  "is_active": true,
                  "expire_at": "2026-07-30T23:59:59.000000Z",
                  "days_left": 2,
                  "device_limit": 3,
                  "total_device_limit": 3,
                  "devices_count": -1,
                  "autopay_enabled": false,
                  "renewal_disabled": true,
                  "plan_archived": false,
                  "subscription_link": "https://example.test/sub",
                  "traffic": {
                    "used_bytes": 0,
                    "limit_bytes": 16106127360,
                    "used_percent": 0.0,
                    "is_unlimited": false
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val response = SubscriptionsJson.parseResponse(json)
        assertTrue(response.autopayAvailable)
        val item = response.selectPreferred()
        assertNotNull(item)
        requireNotNull(item)

        assertEquals("42", item.id)
        assertEquals("sub-name", item.name)
        assertEquals("Trial", item.planName)
        assertEquals("Trial", item.displayName)
        assertEquals("trial", item.kind)
        assertTrue(item.isPrimary)
        assertTrue(item.isActive)
        assertFalse(item.planArchived)
        assertEquals(2, item.daysLeft)
        assertEquals(3, item.effectiveDeviceLimit)
        assertEquals(-1, item.devicesCount)
        assertFalse(item.autopayEnabled)
        assertTrue(item.renewalDisabled)
        assertEquals("https://example.test/sub", item.subscriptionLink)
        assertNotNull(item.expireAtEpochMillis)
        assertTrue(item.expireAtEpochMillis!! > 0L)

        val traffic = item.traffic
        assertNotNull(traffic)
        requireNotNull(traffic)
        assertEquals(0L, traffic.usedBytes)
        assertEquals(16106127360L, traffic.limitBytes)
        assertFalse(traffic.isUnlimited)
    }

    @Test
    fun displayName_prefersPlanNameOverName() {
        val item = SubscriptionItem(
            id = "1",
            name = "legacy",
            planName = "Pro",
            planType = null,
            kind = null,
            isPrimary = true,
            isActive = true,
            expireAtEpochMillis = null,
            daysLeft = null,
            deviceLimit = 1,
            totalDeviceLimit = null,
            devicesCount = null,
            traffic = null,
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = false,
            subscriptionLink = "https://x",
        )
        assertEquals("Pro", item.displayName)
        assertEquals(1, item.effectiveDeviceLimit)
    }

    @Test
    fun selectPreferred_primaryWithLinkWins() {
        val primary = sampleItem(id = "1", primary = true, link = "https://a")
        val other = sampleItem(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(other, primary))
        assertEquals("1", response.selectPreferred()?.id)
    }

    @Test
    fun selectPreferred_skipsPrimaryWithoutLink() {
        val primaryNoLink = sampleItem(id = "1", primary = true, link = null)
        val secondary = sampleItem(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(primaryNoLink, secondary))
        assertEquals("2", response.selectPreferred()?.id)
    }

    /**
     * The import path filters candidates during selection. Without it the first
     * primary wins on having *a* link, is rejected right after, and the working
     * subscription behind it is never reached.
     */
    @Test
    fun selectPreferred_skipsUnusablePrimary_andFallsThroughToTheNextOne() {
        val unusablePrimary = sampleItem(id = "1", primary = true, link = "javascript:x")
        val usable = sampleItem(id = "2", primary = false, link = "https://b")
        val response = SubscriptionsResponse(false, listOf(unusablePrimary, usable))

        assertEquals("2", response.selectPreferred(::looksImportable)?.id)
        // Unfiltered selection is unchanged — the UI still shows the primary.
        assertEquals("1", response.selectPreferred()?.id)
    }

    @Test
    fun selectPreferred_stillPrefersPrimary_amongUsableCandidates() {
        val usableSecondary = sampleItem(id = "1", primary = false, link = "https://a")
        val unusableSecondary = sampleItem(id = "2", primary = false, link = "javascript:x")
        val usablePrimary = sampleItem(id = "3", primary = true, link = "https://c")
        val response = SubscriptionsResponse(
            false,
            listOf(usableSecondary, unusableSecondary, usablePrimary),
        )

        assertEquals("3", response.selectPreferred(::looksImportable)?.id)
    }

    @Test
    fun selectPreferred_nullWhenNoCandidateIsUsable() {
        val response = SubscriptionsResponse(
            false,
            listOf(
                sampleItem(id = "1", primary = true, link = "javascript:x"),
                sampleItem(id = "2", primary = false, link = "ftp://host/sub"),
            ),
        )

        assertNull(response.selectPreferred(::looksImportable))
    }

    /** Stand-in for the host policy: this test pins selection, not the allowlist. */
    private fun looksImportable(item: SubscriptionItem): Boolean =
        item.subscriptionLink?.startsWith("https://") == true

    @Test
    fun selectPreferred_emptyWhenNoLinks() {
        val response = SubscriptionsResponse(
            false,
            listOf(sampleItem(id = "1", primary = true, link = null)),
        )
        assertNull(response.selectPreferred())
    }

    @Test
    fun numberToEpochMillis_secondsVsMillis() {
        assertEquals(1_700_000_000_000L, SubscriptionsJson.numberToEpochMillis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, SubscriptionsJson.numberToEpochMillis(1_700_000_000_000L))
    }

    @Test
    fun parseDateTimeMillis_isoWithFractionalSeconds() {
        val millis = SubscriptionsJson.parseDateTimeMillis("2026-07-30T23:59:59.000000Z")
        assertNotNull(millis)
        assertTrue(millis!! > 0L)
    }

    @Test
    fun normalizeFractionalSeconds_preservesNumericOffset() {
        assertEquals(
            "2026-07-30T23:59:59.123+03:00",
            SubscriptionsJson.normalizeFractionalSeconds(
                "2026-07-30T23:59:59.123456+03:00",
            ),
        )
        assertEquals(
            "2026-07-30T23:59:59.123-05:00",
            SubscriptionsJson.normalizeFractionalSeconds(
                "2026-07-30T23:59:59.123456-05:00",
            ),
        )
        assertEquals(
            "2026-07-30T23:59:59.123Z",
            SubscriptionsJson.normalizeFractionalSeconds(
                "2026-07-30T23:59:59.123456Z",
            ),
        )
    }

    @Test
    fun parseDateTimeMillis_isoWithFractionalSecondsAndOffset() {
        val millis = SubscriptionsJson.parseDateTimeMillis(
            "2026-07-30T23:59:59.123456+03:00",
        )
        assertNotNull(millis)
        // Same instant as 2026-07-30T20:59:59.123Z
        val utc = SubscriptionsJson.parseDateTimeMillis("2026-07-30T20:59:59.123Z")
        assertNotNull(utc)
        assertEquals(utc, millis)
    }

    @Test
    fun parseDateTimeMillis_offsetWithMinutes_equalsUtc() {
        // +03:30 must not be truncated to +03:00 (30 minutes late).
        val withOffset = SubscriptionsJson.parseDateTimeMillis(
            "2026-07-30T23:59:59+03:30",
        )
        val utc = SubscriptionsJson.parseDateTimeMillis("2026-07-30T20:29:59Z")
        assertNotNull(withOffset)
        assertNotNull(utc)
        assertEquals(utc, withOffset)

        val fractional = SubscriptionsJson.parseDateTimeMillis(
            "2026-07-30T23:59:59.123456+03:30",
        )
        val fractionalUtc = SubscriptionsJson.parseDateTimeMillis(
            "2026-07-30T20:29:59.123Z",
        )
        assertNotNull(fractional)
        assertNotNull(fractionalUtc)
        assertEquals(fractionalUtc, fractional)
    }

    @Test
    fun parseDateTimeMillis_compactOffset_equalsColonOffset() {
        val compact = SubscriptionsJson.parseDateTimeMillis("2026-07-30T12:00:00+0530")
        val colon = SubscriptionsJson.parseDateTimeMillis("2026-07-30T12:00:00+05:30")
        assertNotNull(compact)
        assertNotNull(colon)
        assertEquals(colon, compact)
    }

    @Test
    fun planArchived_doesNotAffectIsActive() {
        val json = JSONObject(
            """
            {
              "id": 1,
              "is_active": true,
              "plan_archived": true,
              "subscription_link": "https://x"
            }
            """.trimIndent()
        )
        val item = SubscriptionsJson.parseItem(json)
        assertTrue(item.isActive)
        assertTrue(item.planArchived)
    }

    private fun sampleItem(
        id: String,
        primary: Boolean,
        link: String?,
    ): SubscriptionItem {
        return SubscriptionItem(
            id = id,
            name = null,
            planName = "X",
            planType = null,
            kind = null,
            isPrimary = primary,
            isActive = true,
            expireAtEpochMillis = null,
            daysLeft = null,
            deviceLimit = null,
            totalDeviceLimit = null,
            devicesCount = null,
            traffic = null,
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = false,
            subscriptionLink = link,
        )
    }
}
