package pro.getline.vpn.getline.localproxy

/**
 * The one lifecycle hook the local proxy exposes outside its product API, for
 * code that has just confirmed a change of account: sign-out, subscription
 * removal, or a managed profile replaced by another.
 *
 * It takes no UUID and returns nothing to branch on, because the caller is not
 * the one who knows what ownership means here: the module reads the current
 * owner itself, compares it against its own record, and acts only on a
 * mismatch. That is what makes it safe to call after a *failed* removal too —
 * ownership did not change, so nothing is discarded (see plan step 9).
 */
interface LocalLanProxyOwnerIntegration {
    suspend fun reconcileOwner()

    companion object {
        /**
         * For call sites with no local-proxy involvement — tests, and flows
         * exercised without the app's process-wide facade. Production wiring
         * passes the facade.
         */
        val None: LocalLanProxyOwnerIntegration = object : LocalLanProxyOwnerIntegration {
            override suspend fun reconcileOwner() = Unit
        }
    }
}
