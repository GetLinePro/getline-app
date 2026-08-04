package pro.getline.vpn.getline

/** Where a cold start lands. */
internal enum class LaunchTarget {
    Onboarding,
    Home,
    Advanced,
}

/**
 * Routing-relevant local state, read once per launch.
 *
 * [storeOk] is false when [pro.getline.vpn.getline.auth.GetLineSessionStore]
 * construction or the routing reads failed (keystore, corrupt prefs). Every other
 * field is then a default, **not** a proven empty session — the difference decides
 * whether a user with a working profile is sent back to Onboarding.
 */
internal data class SessionRoutingSnapshot(
    val storeOk: Boolean = false,
    val hasSession: Boolean = false,
    val hasManagedProfile: Boolean = false,
    val hasPendingImport: Boolean = false,
    /** Encrypted session was unreadable and was reset once during this launch. */
    val sessionStorageRecovered: Boolean = false,
    /** Usable encrypted backend: enc|na. */
    val prefsBackend: String = "na",
    /** Legacy plaintext pref file still exists on disk: 1|0|na. */
    val prefsOther: String = "na",
    /** Non-keyset entries physically in the backing file; -1 unparsable, na unread. */
    val prefsRaw: String = "na",
    /** Hours since the backing file was last written; -1 absent, na unread. */
    val prefsAgeHours: String = "na",
)

/**
 * The routing decision plus everything the `startup_route` breadcrumb prints.
 *
 * [reason] and the log tokens travel with the target on purpose: they used to be
 * assembled at each `return`, and a branch added later could route one way and
 * report another. GL-19 reads this line to reconstruct a launch it cannot observe.
 *
 * [snapshot] is null when the store was never read — the fast Advanced path. The
 * log prints `na` for those fields rather than inventing zeros.
 */
internal data class LaunchRoute(
    val target: LaunchTarget,
    val reason: String,
    val snapshot: SessionRoutingSnapshot? = null,
    val backendUnavailable: Boolean = false,
    /** Backend answer for the breadcrumb: 1|0|na. */
    val imported: String = "na",
    /** Backend reachability for the breadcrumb: ok|unavailable|na. */
    val backend: String = "na",
)

/**
 * Cold-start destination. Extracted from MainActivity so the priority order is a
 * table that can be read and tested, rather than five returns inside a UI class.
 */
internal object StartupRoutingPolicy {

    /**
     * Both inputs are suppliers because *not* asking is part of the contract:
     * Advanced must not touch the session store (MasterKey init is not free), and
     * no branch above the backend query may pay for an IPC round trip to a
     * `:background` process that might be dead. A test can therefore assert the
     * question was never asked.
     */
    suspend fun decide(
        openAdvanced: Boolean,
        readSnapshot: suspend () -> SessionRoutingSnapshot,
        hasImported: suspend () -> GetLineBackendResult<Boolean>,
    ): LaunchRoute {
        if (openAdvanced) {
            return LaunchRoute(target = LaunchTarget.Advanced, reason = "open_advanced")
        }

        val snapshot = readSnapshot()

        // Do not open Home/Onboarding flows that immediately construct the same
        // broken store and crash. Onboarding owns the explicit retry UI.
        if (!snapshot.storeOk) {
            return LaunchRoute(
                target = LaunchTarget.Onboarding,
                reason = "session_storage_unavailable",
                snapshot = snapshot,
            )
        }

        // In-flight durable import (first-time or UseAccount) resumes on Onboarding.
        // Checked before managed-profile → Home so a mid-import cold start does not
        // drop the pending UseAccount orphan-cleanup payload.
        if (snapshot.hasPendingImport) {
            return LaunchRoute(
                target = LaunchTarget.Onboarding,
                reason = "pending_import",
                snapshot = snapshot,
            )
        }

        // Managed binding wins. Do NOT force Onboarding solely because the post-login
        // step is incomplete (session + still-link-only): permanent failures
        // (no importable subscription / offline) would trap a working VPN profile
        // forever with only Retry/Diagnostics. Mid-dialog process death may land
        // Home with a temporary session+link-only mix; VPN stays reachable.
        if (snapshot.hasManagedProfile) {
            return LaunchRoute(
                target = LaunchTarget.Home,
                reason = "managed_profile",
                snapshot = snapshot,
            )
        }

        return when (val imported = hasImported()) {
            // A dead backend is not an empty account. Home in a recoverable state
            // beats Onboarding, which would offer to import over an existing profile.
            GetLineBackendResult.Unavailable -> LaunchRoute(
                target = LaunchTarget.Home,
                reason = "backend_unavailable",
                snapshot = snapshot,
                backendUnavailable = true,
                backend = "unavailable",
            )
            is GetLineBackendResult.Success -> if (imported.value) {
                LaunchRoute(
                    target = LaunchTarget.Home,
                    reason = "has_import",
                    snapshot = snapshot,
                    imported = "1",
                    backend = "ok",
                )
            } else {
                LaunchRoute(
                    target = LaunchTarget.Onboarding,
                    reason = "no_import",
                    snapshot = snapshot,
                    imported = "0",
                    backend = "ok",
                )
            }
        }
    }
}
