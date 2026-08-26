package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedBindingSnapshotTest {
    @Test
    fun infer_classifiesSupportedStorageShapes() {
        val cases = listOf(
            Case(
                name = "absent",
                hasSession = false,
                uuid = null,
                source = null,
                subscription = null,
                provenance = ManagedBindingSnapshot.Provenance.Absent,
                hasBinding = false,
                needsPostLogin = false,
                canRemoteRepair = false,
            ),
            Case(
                name = "link-only",
                hasSession = false,
                uuid = "link-uuid",
                source = "https://example.test/link",
                subscription = null,
                provenance = ManagedBindingSnapshot.Provenance.LinkOnly,
                hasBinding = true,
                needsPostLogin = false,
                canRemoteRepair = true,
            ),
            Case(
                name = "post-login-link-only",
                hasSession = true,
                uuid = "link-uuid",
                source = "https://example.test/link",
                subscription = null,
                provenance = ManagedBindingSnapshot.Provenance.LinkOnly,
                hasBinding = true,
                needsPostLogin = true,
                canRemoteRepair = true,
            ),
            Case(
                name = "account-bound",
                hasSession = true,
                uuid = "account-uuid",
                source = null,
                subscription = "subscription-id",
                provenance = ManagedBindingSnapshot.Provenance.AccountBound,
                hasBinding = true,
                needsPostLogin = false,
                canRemoteRepair = true,
            ),
            Case(
                name = "account-bound-with-transient-source",
                hasSession = true,
                uuid = "account-uuid",
                source = "https://example.test/account",
                subscription = "subscription-id",
                provenance = ManagedBindingSnapshot.Provenance.AccountBound,
                hasBinding = true,
                needsPostLogin = false,
                canRemoteRepair = true,
            ),
            Case(
                name = "incomplete-uuid-only",
                hasSession = false,
                uuid = "legacy-uuid",
                source = null,
                subscription = null,
                provenance = ManagedBindingSnapshot.Provenance.InconsistentLegacy,
                hasBinding = true,
                needsPostLogin = false,
                canRemoteRepair = false,
            ),
            Case(
                name = "stray-source",
                hasSession = false,
                uuid = null,
                source = "https://example.test/orphan",
                subscription = null,
                provenance = ManagedBindingSnapshot.Provenance.InconsistentLegacy,
                hasBinding = false,
                needsPostLogin = false,
                canRemoteRepair = true,
            ),
            Case(
                name = "stray-subscription",
                hasSession = false,
                uuid = null,
                source = null,
                subscription = "orphan-subscription",
                provenance = ManagedBindingSnapshot.Provenance.InconsistentLegacy,
                hasBinding = false,
                needsPostLogin = false,
                canRemoteRepair = false,
            ),
        )

        cases.forEach { expected ->
            val actual = ManagedBindingSnapshot.infer(
                hasSession = expected.hasSession,
                managedProfileUuid = expected.uuid,
                managedProfileSource = expected.source,
                subscriptionId = expected.subscription,
            )

            assertEquals(expected.name, expected.provenance, actual.provenance)
            assertEquals(expected.name, expected.hasSession, actual.hasSession)
            assertEquals(expected.name, expected.uuid, actual.managedProfileUuid)
            assertEquals(expected.name, expected.source, actual.managedProfileSource)
            assertEquals(expected.name, expected.subscription, actual.subscriptionId)
            assertEquals(expected.name, expected.hasBinding, actual.hasManagedBinding)
            assertEquals(expected.name, expected.needsPostLogin, actual.needsPostLoginSubscriptionStep)
            assertEquals(expected.name, expected.canRemoteRepair, actual.canRemoteRepair)
        }
    }

    @Test
    fun infer_normalizesBlankLegacyValuesBeforeClassification() {
        val snapshot = ManagedBindingSnapshot.infer(
            hasSession = false,
            managedProfileUuid = "  ",
            managedProfileSource = "",
            subscriptionId = "\t",
        )

        assertEquals(ManagedBindingSnapshot.Provenance.Absent, snapshot.provenance)
        assertNull(snapshot.managedProfileUuid)
        assertNull(snapshot.managedProfileSource)
        assertNull(snapshot.subscriptionId)
        assertFalse(snapshot.hasManagedBinding)
        assertFalse(snapshot.canRemoteRepair)
        assertFalse(snapshot.needsPostLoginSubscriptionStep)
    }

    private data class Case(
        val name: String,
        val hasSession: Boolean,
        val uuid: String?,
        val source: String?,
        val subscription: String?,
        val provenance: ManagedBindingSnapshot.Provenance,
        val hasBinding: Boolean,
        val needsPostLogin: Boolean,
        val canRemoteRepair: Boolean,
    )
}
