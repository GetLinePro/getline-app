package pro.getline.vpn

import com.github.kr328.clash.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.getline.auth.GetLineAuthException

/**
 * Flavor-sensitive isolation tests. Run both:
 * - `:app:testAlphaE2eDebugUnitTest`
 * - `:app:testAlphaProdDebugUnitTest`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GetLineControlPlaneHostPolicyTest {
    private val isE2e: Boolean
        get() = GetLineControlPlaneHostPolicy.isE2e

    @Test
    fun flavorEnvironment_matchesBuildConfig() {
        assertEquals(
            BuildConfig.FLAVOR_environment == "e2e",
            GetLineControlPlaneHostPolicy.isE2e,
        )
    }

    @Test
    fun e2e_acceptsAppStage() {
        if (!isE2e) return
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("app.stage.getline.pro"),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHttpsUrl(
                "https://app.stage.getline.pro/sub/e2e",
            ),
        )
    }

    @Test
    fun e2e_acceptsAuthStage() {
        if (!isE2e) return
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("auth.stage.getline.pro"),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost(
                "auth.stage.getline.pro",
            ),
        )
    }

    @Test
    fun e2e_rejectsAppProduction() {
        if (!isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("app.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost("app.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHttpsUrl(
                "https://app.getline.pro/api/subscriptions",
            ),
        )
    }

    @Test
    fun e2e_rejectsBotGetLine() {
        if (!isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("bot.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost("bot.getline.pro"),
        )
    }

    @Test
    fun e2e_rejectsAuthProduction() {
        if (!isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("auth.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost("auth.getline.pro"),
        )
    }

    @Test
    fun e2e_rejectsArbitraryHost() {
        if (!isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("evil.example"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost("accounts.google.com"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHttpsUrl(
                "https://evil.example/sub",
            ),
        )
    }

    @Test
    fun prod_acceptsExpectedProductionHost() {
        if (isE2e) return
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("app.getline.pro"),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHttpsUrl(
                "https://app.getline.pro/",
            ),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost("app.getline.pro"),
        )
    }

    @Test
    fun prod_acceptsDedicatedCallbackHost() {
        if (isE2e) return
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("auth.getline.pro"),
        )
        assertEquals(
            AppEnvironment.callbackHost,
            "auth.getline.pro",
        )
        // Trampolines stay on the portal host; only completion moves.
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchUrl(
                AppEnvironment.googleTrampolineUrl,
            ),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchUrl(
                AppEnvironment.telegramTrampolineUrl,
            ),
        )
    }

    @Test
    fun prod_rejectsStageAndBotHosts() {
        if (isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("app.stage.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("auth.stage.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("bot.getline.pro"),
        )
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost(
                "app.stage.getline.pro",
            ),
        )
    }

    @Test
    fun prod_allowsThirdPartyOauthBrowserHost() {
        if (isE2e) return
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost(
                "accounts.google.com",
            ),
        )
    }

    @Test
    fun subscriptionUrl_policyRejectsWrongEnvironment() {
        if (isE2e) {
            try {
                GetLineControlPlaneHostPolicy.requireSubscriptionUrl(
                    "https://app.getline.pro/sub/real",
                )
                fail("e2e must reject production subscription URL")
            } catch (_: GetLineAuthException.Protocol) {
                // expected
            }
        } else {
            try {
                GetLineControlPlaneHostPolicy.requireSubscriptionUrl(
                    "https://app.stage.getline.pro/sub/e2e",
                )
                fail("prod must reject stage subscription URL")
            } catch (_: GetLineAuthException.Protocol) {
                // expected
            }
        }
    }

    /**
     * The import link host is RWP's, not one of our two control-plane hosts —
     * pinning it to [GetLineControlPlaneHostPolicy.prodAllowedHosts] broke every
     * production import.
     */
    @Test
    fun prod_acceptsSubscriptionHostOutsideControlPlaneAllowlist() {
        if (isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedProductHost("sub.getline.pro"),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost("sub.getline.pro"),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost("bot.getline.pro"),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost("getline.pro"),
        )
    }

    @Test
    fun subscriptionHost_rejectsForeignAndStageForms() {
        assertFalse(GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost(null))
        assertFalse(GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost("evil.com"))
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost(
                "getline.pro.evil.com",
            ),
        )
        if (!isE2e) {
            // Trailing-dot FQDN must not slip past the stage exclusion.
            assertFalse(
                GetLineControlPlaneHostPolicy.isAllowedSubscriptionHost(
                    "sub.stage.getline.pro.",
                ),
            )
        }
    }

    @Test
    fun subscriptionUrl_requiresHttps() {
        try {
            GetLineControlPlaneHostPolicy.requireSubscriptionUrl(
                "http://sub.getline.pro/sub/x",
            )
            fail("plain http subscription URL must be rejected")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun requireBrowserLaunchUrl_rejectsWrongEnvironmentGetLineHost() {
        val foreign = if (isE2e) {
            "https://app.getline.pro/__mock__/google"
        } else {
            "https://auth.stage.getline.pro/__mock__/google"
        }
        try {
            GetLineControlPlaneHostPolicy.requireBrowserLaunchUrl(foreign)
            fail("must reject foreign environment auth_url")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun appEnvironment_originsMatchFlavor() {
        if (isE2e) {
            assertEquals("https://app.stage.getline.pro", AppEnvironment.apiOrigin)
            assertEquals("https://auth.stage.getline.pro", AppEnvironment.authOrigin)
            assertEquals("auth.stage.getline.pro", AppEnvironment.callbackHost)
            assertEquals("https://app.stage.getline.pro", AppEnvironment.portalOrigin)
        } else {
            assertEquals("https://app.getline.pro", AppEnvironment.apiOrigin)
            assertEquals("https://app.getline.pro", AppEnvironment.authOrigin)
            // Callback host is deliberately not the portal host: the portal
            // manifest scope "/" lets a WebAPK claim that whole domain.
            assertEquals("auth.getline.pro", AppEnvironment.callbackHost)
            assertEquals("https://app.getline.pro", AppEnvironment.portalOrigin)
        }
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHttpsUrl(
                AppEnvironment.apiOrigin,
            ),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHost(
                AppEnvironment.callbackHost,
            ),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHttpsUrl(
                AppEnvironment.portalOrigin,
            ),
        )
    }

    @Test
    fun canonicalizeHost_stripsTrailingDots() {
        assertEquals(
            "auth.stage.getline.pro",
            GetLineControlPlaneHostPolicy.canonicalizeHost("auth.stage.getline.pro."),
        )
        assertEquals(
            "app.getline.pro",
            GetLineControlPlaneHostPolicy.canonicalizeHost("App.GetLine.Pro..."),
        )
    }

    @Test
    fun trailingDotFqdn_classifiedAsGetLineFamily() {
        assertTrue(
            GetLineControlPlaneHostPolicy.isGetLineFamilyHost(
                "auth.stage.getline.pro.",
            ),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isGetLineFamilyHost("bot.getline.pro."),
        )
    }

    @Test
    fun prod_rejectsTrailingDotStageHostForBrowserLaunch() {
        if (isE2e) return
        assertFalse(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost(
                "auth.stage.getline.pro.",
            ),
        )
        try {
            GetLineControlPlaneHostPolicy.requireBrowserLaunchUrl(
                "https://auth.stage.getline.pro./__mock__/google",
            )
            fail("trailing-dot stage host must be rejected on prod")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun e2e_acceptsTrailingDotStageHost() {
        if (!isE2e) return
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedProductHost(
                "app.stage.getline.pro.",
            ),
        )
        assertTrue(
            GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchHost(
                "auth.stage.getline.pro.",
            ),
        )
    }

    @Test
    fun applicationId_matchesExpectedPackageForSmokeVariants() {
        // Not every flavor is asserted; only the two packages called out by S4.
        when {
            BuildConfig.FLAVOR == "alphaE2e" && BuildConfig.BUILD_TYPE == "debug" ->
                assertEquals(
                    "pro.getline.vpn.alpha.e2e.debug",
                    BuildConfig.APPLICATION_ID,
                )
            BuildConfig.FLAVOR == "metaProd" && BuildConfig.BUILD_TYPE == "release" ->
                assertEquals("pro.getline.vpn", BuildConfig.APPLICATION_ID)
            else -> {
                // Other variants still must not claim e2e package when prod, etc.
                if (!isE2e) {
                    assertFalse(
                        BuildConfig.APPLICATION_ID.contains(".e2e."),
                    )
                }
            }
        }
    }
}
