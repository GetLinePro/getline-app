package pro.getline.vpn.getline.accountportal

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.AppEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountPortalUriPolicyTest {
    private val portalHost: String
        get() = AccountPortalUriPolicy.expectedHost

    private val portalOrigin: String
        get() = AppEnvironment.portalOrigin.trimEnd('/')

    @Test
    fun dashboardUri_allowedAndHasNoSecrets() {
        val uri = AccountPortalUriPolicy.dashboardUri()
        assertTrue(AccountPortalUriPolicy.isAllowedPortalUri(uri))
        assertFalse(AccountPortalUriPolicy.containsSecrets(uri))
        assertEquals("https", uri.scheme)
        assertEquals(portalHost, uri.host)
        assertEquals("/my-dashboard", uri.fragment)
        assertTrue(uri.query.isNullOrEmpty())
        assertTrue(uri.userInfo.isNullOrEmpty())
    }

    @Test
    fun httpsDashboard_allowed() {
        val uri = Uri.parse("$portalOrigin/#/my-dashboard")
        assertTrue(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun cleartextHttp_rejected() {
        val uri = Uri.parse("http://$portalHost/#/my-dashboard")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun evilHost_rejected() {
        val uri = Uri.parse("https://evil.example/#/my-dashboard")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun hostSuffixSpoof_rejected() {
        val uri = Uri.parse("https://$portalHost.evil.example/#/my-dashboard")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun userinfoSpoof_rejected() {
        // https://{portal}@evil.example/ — host is evil.example, userinfo present
        val uri = Uri.parse("https://$portalHost@evil.example/#/my-dashboard")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun subdomain_rejected() {
        val uri = Uri.parse("https://evil.$portalHost/#/my-dashboard")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun javascriptScheme_rejected() {
        val uri = Uri.parse("javascript:alert(1)")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun tokenInQuery_detectedAsSecret() {
        val uri = Uri.parse("$portalOrigin/?access_token=secret")
        assertTrue(AccountPortalUriPolicy.isAllowedPortalUri(uri))
        assertTrue(AccountPortalUriPolicy.containsSecrets(uri))
    }

    @Test
    fun tokenInFragment_detectedAsSecret() {
        val uri = Uri.parse("$portalOrigin/#/login?auth_token=secret")
        assertTrue(AccountPortalUriPolicy.containsSecrets(uri))
    }

    @Test
    fun nonDefaultPort_rejected() {
        val uri = Uri.parse("https://$portalHost:8443/#/my-dashboard")
        assertFalse(AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }
}
