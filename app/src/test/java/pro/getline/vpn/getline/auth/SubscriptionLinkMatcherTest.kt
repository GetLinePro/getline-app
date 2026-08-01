package pro.getline.vpn.getline.auth

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
class SubscriptionLinkMatcherTest {
    @Test
    fun canonical_stripsTrailingSlash() {
        assertEquals(
            "https://sub.example.com/path/token",
            SubscriptionLinkMatcher.canonical("https://sub.example.com/path/token/"),
        )
    }

    @Test
    fun canonical_lowercasesHost() {
        assertEquals(
            "https://sub.example.com/path",
            SubscriptionLinkMatcher.canonical("https://Sub.Example.COM/path"),
        )
    }

    @Test
    fun canonical_stripsTrailingHostDots() {
        assertEquals(
            "https://sub.example.com/path",
            SubscriptionLinkMatcher.canonical("https://sub.example.com./path"),
        )
    }

    @Test
    fun canonical_omitsDefaultHttpsPort() {
        assertEquals(
            "https://sub.example.com/path",
            SubscriptionLinkMatcher.canonical("https://sub.example.com:443/path"),
        )
        assertEquals(
            SubscriptionLinkMatcher.canonical("https://sub.example.com/path"),
            SubscriptionLinkMatcher.canonical("https://sub.example.com:443/path"),
        )
    }

    @Test
    fun canonical_keepsNonDefaultPort() {
        assertEquals(
            "https://sub.example.com:8443/path",
            SubscriptionLinkMatcher.canonical("https://sub.example.com:8443/path"),
        )
    }

    @Test
    fun canonical_dropsFragment() {
        assertEquals(
            "https://sub.example.com/path?q=1",
            SubscriptionLinkMatcher.canonical("https://sub.example.com/path?q=1#frag"),
        )
    }

    @Test
    fun matchesAny_sameUrlDifferentFragment_matches() {
        val source = "https://sub.example.com/s/abc#local"
        val items = listOf(item("https://sub.example.com/s/abc"))
        assertTrue(SubscriptionLinkMatcher.matchesAny(source, items))
    }

    @Test
    fun matchesAny_differentQuery_noMatch() {
        val source = "https://sub.example.com/s/abc?token=a"
        val items = listOf(item("https://sub.example.com/s/abc?token=b"))
        assertFalse(SubscriptionLinkMatcher.matchesAny(source, items))
    }

    @Test
    fun matchesAny_queryOrderPreserved_noReshuffle() {
        val source = "https://sub.example.com/s?b=2&a=1"
        val items = listOf(item("https://sub.example.com/s?a=1&b=2"))
        assertFalse(SubscriptionLinkMatcher.matchesAny(source, items))
    }

    @Test
    fun canonical_httpRejected() {
        assertNull(SubscriptionLinkMatcher.canonical("http://sub.example.com/path"))
    }

    @Test
    fun matchesAny_httpSource_noMatch() {
        assertFalse(
            SubscriptionLinkMatcher.matchesAny(
                "http://sub.example.com/path",
                listOf(item("https://sub.example.com/path")),
            ),
        )
    }

    @Test
    fun canonical_userInfoRejected() {
        assertNull(SubscriptionLinkMatcher.canonical("https://user:pass@sub.example.com/path"))
        assertNull(SubscriptionLinkMatcher.canonical("https://user@sub.example.com/path"))
    }

    @Test
    fun matchesAny_emptyList_false() {
        assertFalse(
            SubscriptionLinkMatcher.matchesAny("https://sub.example.com/path", emptyList()),
        )
    }

    @Test
    fun matchesAny_nullOrBlankSource_false() {
        assertFalse(SubscriptionLinkMatcher.matchesAny(null, listOf(item("https://a/b"))))
        assertFalse(SubscriptionLinkMatcher.matchesAny("  ", listOf(item("https://a/b"))))
    }

    @Test
    fun matchesAny_matchIsNotFirst() {
        val source = "https://sub.example.com/second"
        val items = listOf(
            item("https://sub.example.com/first", id = "1"),
            item("https://sub.example.com/second", id = "2"),
            item("https://sub.example.com/third", id = "3"),
        )
        assertTrue(SubscriptionLinkMatcher.matchesAny(source, items))
    }

    @Test
    fun findMatch_returnsSecondaryItemNotFirst() {
        val source = "https://sub.example.com/second"
        val items = listOf(
            item("https://sub.example.com/first", id = "pref", primary = true),
            item("https://sub.example.com/second", id = "sec", primary = false),
        )
        val matched = SubscriptionLinkMatcher.findMatch(source, items)
        assertEquals("sec", matched?.id)
        assertEquals("https://sub.example.com/second", matched?.subscriptionLink)
    }

    @Test
    fun findMatch_noMatch_null() {
        assertNull(
            SubscriptionLinkMatcher.findMatch(
                "https://sub.example.com/missing",
                listOf(item("https://sub.example.com/other")),
            ),
        )
    }

    @Test
    fun canonical_nullEmpty() {
        assertNull(SubscriptionLinkMatcher.canonical(null))
        assertNull(SubscriptionLinkMatcher.canonical(""))
        assertNull(SubscriptionLinkMatcher.canonical("   "))
    }

    @Test
    fun canonical_notNullForPlainHttps() {
        assertNotNull(SubscriptionLinkMatcher.canonical("https://sub.example.com/x"))
    }

    private fun item(
        link: String?,
        id: String = "1",
        primary: Boolean = true,
    ): SubscriptionItem {
        return SubscriptionItem(
            id = id,
            name = null,
            planName = "P",
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
