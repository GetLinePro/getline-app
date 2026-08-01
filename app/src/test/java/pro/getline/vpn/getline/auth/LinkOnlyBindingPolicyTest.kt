package pro.getline.vpn.getline.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkOnlyBindingPolicyTest {
    @Test
    fun linkOnly_whenUuidAndSourceWithoutSubscriptionId() {
        assertTrue(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = null,
            ),
        )
        assertTrue(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = "",
            ),
        )
        assertTrue(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = "   ",
            ),
        )
    }

    @Test
    fun notLinkOnly_whenSubscriptionIdPresent() {
        assertFalse(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = "sub-9",
            ),
        )
    }

    @Test
    fun notLinkOnly_whenUuidMissing() {
        assertFalse(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = null,
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = null,
            ),
        )
        assertFalse(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "  ",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = null,
            ),
        )
    }

    @Test
    fun notLinkOnly_whenSourceMissing() {
        assertFalse(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "uuid-1",
                managedSource = null,
                rememberedSubscriptionId = null,
            ),
        )
        assertFalse(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                managedUuid = "uuid-1",
                managedSource = "",
                rememberedSubscriptionId = null,
            ),
        )
    }

    @Test
    fun needsPostLoginSubscriptionStep_onlyWithSessionAndLinkOnly() {
        assertTrue(
            LinkOnlyBindingPolicy.needsPostLoginSubscriptionStep(
                hasSession = true,
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = null,
            ),
        )
        assertFalse(
            LinkOnlyBindingPolicy.needsPostLoginSubscriptionStep(
                hasSession = false,
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = null,
            ),
        )
        assertFalse(
            LinkOnlyBindingPolicy.needsPostLoginSubscriptionStep(
                hasSession = true,
                managedUuid = "uuid-1",
                managedSource = "https://sub.example.com/s",
                rememberedSubscriptionId = "sub-9",
            ),
        )
        assertFalse(
            LinkOnlyBindingPolicy.needsPostLoginSubscriptionStep(
                hasSession = true,
                managedUuid = null,
                managedSource = null,
                rememberedSubscriptionId = null,
            ),
        )
    }
}
