package pro.getline.vpn.getline

/**
 * Pure ladder for GetLine VPN config repair. No CMFA UI, no arbitrary imports.
 *
 * Order:
 * 1. valid active → done
 * 2. managed UUID still imported → local setActive
 * 3. remote re-provision only when local managed profile is proven absent
 *    and a remote path exists (native session or saved URL source)
 * 4. otherwise setup (sign-in / add link) — not Retry
 */
object VpnConfigurationRepairPolicy {

    enum class Step {
        /** Active selection is already valid. */
        Done,
        /** Call setActive(managedUuid) only. */
        LocalActivate,
        /** GET subscriptions / saved URL → reimport managed profile. */
        RemoteReprovision,
        /** Need network for remote, but device is offline. */
        OfflineForRemote,
        /** Nothing to repair; user must sign in or add a link. */
        NeedsSetup,
        /**
         * Binding exists but remote is not allowed this pass
         * (e.g. resume without network repair) or remote path missing after local fail.
         */
        FailedLocalOnly,
    }

    /**
     * @param activeImportedUuid current ServiceStore active if imported
     * @param managedUuid persisted GetLine-managed profile id
     * @param managedIsImported managed UUID exists in ImportedDao
     * @param hasSession native GetLine session present
     * @param hasSavedUrlSource URL-import source persisted for re-import without session
     * @param allowNetwork whether this pass may hit the network (Retry / cold start)
     * @param online validated internet available
     */
    fun plan(
        activeImportedUuid: String?,
        managedUuid: String?,
        managedIsImported: Boolean,
        hasSession: Boolean,
        hasSavedUrlSource: Boolean,
        allowNetwork: Boolean,
        online: Boolean,
    ): Step {
        val managed = managedUuid?.takeIf { it.isNotBlank() }
        val active = activeImportedUuid?.takeIf { it.isNotBlank() }

        if (active != null) return Step.Done

        if (managed != null && managedIsImported) return Step.LocalActivate

        val canRemote = hasSession || hasSavedUrlSource
        if (!canRemote) {
            // Ghost binding (uuid without source) or no binding: Retry cannot invent
            // a subscription. Send the user to setup / sign-in / add link.
            return Step.NeedsSetup
        }

        // Soft fail only when a remote path exists but this pass must stay local
        // (quiet resume). Explicit Retry uses allowNetwork=true.
        if (!allowNetwork) return Step.FailedLocalOnly
        if (!online) return Step.OfflineForRemote
        return Step.RemoteReprovision
    }
}
