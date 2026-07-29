package pro.getline.vpn.getline.servers

/**
 * Product-facing main-group server selection.
 *
 * CMFA/Clash types stay behind the adapter implementation.
 * Main-group policy: [MainProxyGroupPolicy] (applied inside the adapter).
 */
interface VpnServerSelectionRepository {
    /**
     * Load proxies from the product main selector group.
     * Same group resolution as [queryMainSelectedName] / Home location.
     */
    suspend fun loadMainGroup(): VpnServerLoadResult

    /**
     * Selected proxy name in the main group (Home location row).
     * Uses the same main-group policy as [loadMainGroup].
     */
    suspend fun queryMainSelectedName(): String?

    /**
     * Measure latency for the main group so variants can be ranked.
     * Best-effort: a failure leaves the previous delays untouched.
     */
    suspend fun healthCheckMainGroup(): Boolean

    /**
     * Same selection path as ProxyActivity: patchSelector + SelectionDao persistence
     * (inside ClashManager). Does not stop or restart VPN.
     */
    suspend fun select(groupName: String, serverName: String): Boolean
}
