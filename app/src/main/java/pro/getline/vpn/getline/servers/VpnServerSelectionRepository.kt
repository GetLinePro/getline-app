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
     * Same group resolution as [queryMainSelection] / Home location.
     */
    suspend fun loadMainGroup(): VpnServerLoadResult

    /**
     * Selected proxy in the main group (Home location row), resolved through a
     * nested group when the selection is one.
     * Uses the same main-group policy as [loadMainGroup].
     */
    suspend fun queryMainSelection(): VpnMainSelection?

    /**
     * Measure latency for the main group so variants can be ranked.
     * Best-effort: a failure leaves the previous delays untouched.
     */
    suspend fun healthCheckMainGroup(): Boolean

    /**
     * VPN on: patchSelector + SelectionDao (inside ClashManager).
     * VPN off: SelectionDao only, applied on the next Clash.load.
     * Does not start or stop VPN.
     */
    suspend fun select(groupName: String, serverName: String): Boolean
}
