package pro.getline.vpn.getline

import pro.getline.vpn.getline.auth.GetLineSessionRepository

/**
 * Durable terminal writes for a product import.
 *
 * [recordCreatedUuid] is the first local commit after backend Success: the
 * created row survives process death before [rememberManagedProfile]. [commit]
 * then binds and clears pending. Activity keeps VPN/orphan cleanup.
 */
internal object ImportTerminalBinding {
    fun recordCreatedUuid(
        sessions: GetLineSessionRepository,
        uuid: String,
    ): Boolean {
        val id = uuid.takeIf { it.isNotBlank() } ?: return false
        if (sessions.pendingImport() == null) return false
        return sessions.commitCreatedImportUuid(id)
    }

    fun commit(
        sessions: GetLineSessionRepository,
        result: GetLineImportCoordinator.ImportTerminal.Settled,
        source: String?,
    ) {
        when (result) {
            is GetLineImportCoordinator.ImportTerminal.Success -> {
                recordCreatedUuid(sessions, result.id.value)
                val pending = sessions.pendingImport()
                val previous = pending?.previousManagedUuidToDelete
                if (previous != null && previous != result.id.value) {
                    sessions.rememberPendingProfileCleanup(previous)
                }
                sessions.rememberManagedProfile(
                    uuid = result.id.value,
                    source = source,
                )
                pending?.subscriptionIdToRemember?.let { sessions.rememberSubscription(it) }
                sessions.clearPendingImport()
            }
            is GetLineImportCoordinator.ImportTerminal.Unavailable -> {
                if (result.clearsPendingImport()) {
                    sessions.clearPendingImport()
                }
            }
        }
    }
}
