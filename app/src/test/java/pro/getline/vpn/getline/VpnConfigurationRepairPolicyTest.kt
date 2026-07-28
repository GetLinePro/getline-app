package pro.getline.vpn.getline

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.getline.vpn.getline.VpnConfigurationRepairPolicy.Step

class VpnConfigurationRepairPolicyTest {

    @Test
    fun validActive_isDone_withoutRemote() {
        assertEquals(
            Step.Done,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = "active",
                managedUuid = "managed",
                managedIsImported = false,
                hasSession = true,
                hasSavedUrlSource = true,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun lostActive_managedImported_localActivate() {
        assertEquals(
            Step.LocalActivate,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = true,
                hasSession = true,
                hasSavedUrlSource = false,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun managedMissing_withSession_online_remote() {
        assertEquals(
            Step.RemoteReprovision,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = false,
                hasSession = true,
                hasSavedUrlSource = false,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun managedMissing_urlImportOnly_remote() {
        assertEquals(
            Step.RemoteReprovision,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = false,
                hasSession = false,
                hasSavedUrlSource = true,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun managedMissing_offline_isOfflineForRemote() {
        assertEquals(
            Step.OfflineForRemote,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = false,
                hasSession = true,
                hasSavedUrlSource = false,
                allowNetwork = true,
                online = false,
            ),
        )
    }

    @Test
    fun managedMissing_resumeWithoutNetwork_failedLocalOnly() {
        assertEquals(
            Step.FailedLocalOnly,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = false,
                hasSession = true,
                hasSavedUrlSource = false,
                allowNetwork = false,
                online = true,
            ),
        )
    }

    @Test
    fun noBinding_noRemotePath_needsSetup() {
        assertEquals(
            Step.NeedsSetup,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = null,
                managedIsImported = false,
                hasSession = false,
                hasSavedUrlSource = false,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun managedUuid_withoutImport_noRemotePath_needsSetup() {
        // Migrated binding with uuid but no source and no session: Retry is useless.
        assertEquals(
            Step.NeedsSetup,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "ghost",
                managedIsImported = false,
                hasSession = false,
                hasSavedUrlSource = false,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun hasSavedSource_prefersRemoteEvenWithSession() {
        // Provenance is decided at re-provision time; policy only gates canRemote.
        assertEquals(
            Step.RemoteReprovision,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = false,
                hasSession = true,
                hasSavedUrlSource = true,
                allowNetwork = true,
                online = true,
            ),
        )
    }

    @Test
    fun doesNotRemoteWhenLocalActivateAvailable() {
        assertEquals(
            Step.LocalActivate,
            VpnConfigurationRepairPolicy.plan(
                activeImportedUuid = null,
                managedUuid = "managed",
                managedIsImported = true,
                hasSession = true,
                hasSavedUrlSource = true,
                allowNetwork = true,
                online = true,
            ),
        )
    }
}
