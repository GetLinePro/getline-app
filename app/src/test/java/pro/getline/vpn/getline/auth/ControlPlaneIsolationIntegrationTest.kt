package pro.getline.vpn.getline.auth

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.getline.accountportal.AccountPortalUriPolicy

/**
 * Integration-style isolation checks across callback, browser launch, portal,
 * and subscription_link. Flavor-sensitive: run e2e and prod unit test tasks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControlPlaneIsolationIntegrationTest {
    private val isE2e: Boolean
        get() = GetLineControlPlaneHostPolicy.isE2e

    @Test
    fun callbackParser_rejectsWrongHttpsHost() {
        val uri = Uri.parse("https://evil.example/#/login?auth_token=token")
        try {
            AuthCallbackParser.parse(uri)
            fail("callback must reject foreign host")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun callbackParser_acceptsNativeCode() {
        val uri = Uri.parse("${AppEnvironment.nativeCallbackUri}?code=tok")
        val result = AuthCallbackParser.parse(uri)
        assertEquals("tok", (result as AuthCallbackResult.NativeCode).code)
    }

    @Test
    fun callbackParser_acceptsHttpsWebToken() {
        val uri = Uri.parse(
            "https://${AppEnvironment.callbackHost}/#/login?auth_token=tok&expires_in=60",
        )
        val result = AuthCallbackParser.parse(uri)
        assertEquals("tok", (result as AuthCallbackResult.WebToken).authToken)
    }

    @Test
    fun browserLauncher_rejectsUrlForOtherEnvironment() {
        val foreign = if (isE2e) {
            "https://app.getline.pro/oauth/google"
        } else {
            "https://auth.stage.getline.pro/__mock__/google"
        }
        try {
            BrowserAuthLauncher.parseAndValidateLaunchUrl(foreign)
            fail("browser launch must reject other-environment product host")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun browserLauncher_acceptsValidEnvironmentUrl() {
        val ok = if (isE2e) {
            "https://auth.stage.getline.pro/__mock__/google"
        } else {
            "https://accounts.google.com/o/oauth2/auth?client_id=x"
        }
        val uri = BrowserAuthLauncher.parseAndValidateLaunchUrl(ok)
        assertEquals(Uri.parse(ok).host, uri.host)
    }

    @Test
    fun accountPortal_rejectsProductionUrlOnE2e() {
        if (!isE2e) return
        val uri = Uri.parse("https://app.getline.pro/#/my-dashboard")
        assertEquals(false, AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun accountPortal_rejectsStageUrlOnProd() {
        if (isE2e) return
        val uri = Uri.parse("https://app.stage.getline.pro/#/my-dashboard")
        assertEquals(false, AccountPortalUriPolicy.isAllowedPortalUri(uri))
    }

    @Test
    fun subscriptionUrl_policyRejectsProductionInE2e() {
        if (!isE2e) return
        try {
            GetLineControlPlaneHostPolicy.requireSubscriptionUrl(
                "https://app.getline.pro/sub/paid",
            )
            fail("e2e must reject production subscription_link")
        } catch (e: GetLineAuthException.Protocol) {
            assertEquals(true, e.message!!.contains("subscription_link"))
        }
    }

    @Test
    fun rwpApi_rejectsForeignOriginAtConstruction() {
        val foreign = if (isE2e) {
            "https://app.getline.pro"
        } else {
            "https://app.stage.getline.pro"
        }
        try {
            RwpGetLineAuthApi(origin = foreign)
            fail("RwpGetLineAuthApi must reject foreign API origin")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun rwpApi_acceptsEnvironmentApiOrigin() {
        // Construction only — no network.
        RwpGetLineAuthApi(origin = AppEnvironment.apiOrigin)
    }
}
