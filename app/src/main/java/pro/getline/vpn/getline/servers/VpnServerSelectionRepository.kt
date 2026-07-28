package pro.getline.vpn.getline.servers

import pro.getline.vpn.core.model.ProxyGroup
import pro.getline.vpn.core.model.ProxySort
import pro.getline.vpn.service.remote.IClashManager
import pro.getline.vpn.util.withClash
import kotlinx.coroutines.CancellationException

/**
 * Thin adapter over the same Clash APIs used by [pro.getline.vpn.ProxyActivity].
 * Does not start latency tests or open the legacy Proxy UI.
 *
 * Main-group selection is centralized in [MainProxyGroupPolicy] + [queryMainProxyGroup].
 */
class VpnServerSelectionRepository {
    /**
     * Load proxies from the product main selector group.
     * Same group resolution as [queryMainSelectedName] / Home location.
     */
    suspend fun loadMainGroup(
        excludeNotSelectable: Boolean,
        sort: ProxySort,
    ): VpnServerLoadResult {
        return try {
            withClash {
                val resolved = queryMainProxyGroup(excludeNotSelectable, sort)
                    ?: return@withClash VpnServerLoadResult.Empty
                val (groupName, proxyGroup) = resolved
                val servers = proxyGroup.proxies.map { proxy ->
                    // Raw name stays the identity Home uses via .now; grouping is
                    // derived from it in ServerNameParser, not here.
                    VpnServerItem(
                        name = proxy.name,
                        displayName = proxy.name,
                        delayMs = proxy.delay,
                        protocol = proxy.type.takeIf { it.isNotBlank() },
                        isGroup = proxy.isGroup,
                        // A nested group (e.g. "⚡ Авто") hides which node actually
                        // carries traffic; resolve it so the UI can name it.
                        resolvedName = if (proxy.isGroup) {
                            runCatching {
                                resolveLeafName(proxy.name) { queryProxyGroup(it, sort) }
                            }.getOrNull()
                        } else {
                            null
                        },
                    )
                }
                if (servers.isEmpty()) {
                    VpnServerLoadResult.Empty
                } else {
                    VpnServerLoadResult.Success(
                        groupName = groupName,
                        servers = servers,
                        selectedName = proxyGroup.now,
                        selectable = proxyGroup.type == SELECTOR_TYPE,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            VpnServerLoadResult.Failed
        }
    }

    /**
     * Selected proxy name in the main group (Home location row).
     * Uses the same main-group policy as [loadMainGroup].
     */
    suspend fun queryMainSelectedName(
        excludeNotSelectable: Boolean,
        sort: ProxySort,
    ): String? {
        return try {
            withClash {
                val resolved = queryMainProxyGroup(excludeNotSelectable, sort)
                    ?: return@withClash null
                resolved.second.now.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Measure latency for the main group so variants can be ranked.
     * Best-effort: a failure leaves the previous delays untouched.
     */
    suspend fun healthCheckMainGroup(
        excludeNotSelectable: Boolean,
        sort: ProxySort,
    ): Boolean {
        return try {
            withClash {
                val groupName = queryMainProxyGroup(excludeNotSelectable, sort)?.first
                    ?: return@withClash false
                healthCheck(groupName)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Same selection path as ProxyActivity: patchSelector + SelectionDao persistence
     * (inside ClashManager). Does not stop or restart VPN.
     */
    suspend fun select(groupName: String, serverName: String): Boolean {
        return try {
            withClash {
                patchSelector(groupName, serverName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val SELECTOR_TYPE = "Selector"

        /**
         * Depth cap for group-to-group chains. Real configs nest one or two
         * levels (Youtube -> VPN -> node); this only bounds pathological ones.
         */
        private const val MAX_RESOLVE_DEPTH = 8

        /**
         * Follow a group's selection down to the node actually carrying traffic.
         *
         * A group's `now` names its direct child, which may be another group, so
         * a single hop would report the intermediate group instead of the leaf.
         * Descends while the child is itself a group.
         *
         * @return the leaf name, or null on a cycle, a missing group, or a chain
         *   deeper than [MAX_RESOLVE_DEPTH] — reporting nothing beats reporting
         *   a node that is not the one in use.
         */
        internal fun resolveLeafName(
            startGroupName: String,
            maxDepth: Int = MAX_RESOLVE_DEPTH,
            queryGroup: (String) -> ProxyGroup?,
        ): String? {
            val visited = mutableSetOf<String>()
            var current = startGroupName

            repeat(maxDepth) {
                // A group reachable from itself would otherwise loop forever.
                if (!visited.add(current)) return null

                val group = queryGroup(current) ?: return null
                val now = group.now.takeIf { it.isNotBlank() } ?: return null

                val child = group.proxies.firstOrNull { it.name == now }
                // Unknown child: report what the group says rather than nothing.
                if (child == null || !child.isGroup) return now

                current = now
            }
            return null
        }

        /**
         * Central Clash entry for the product main proxy group.
         * Prefer known product names that are Selectors; else first Selector; else first name.
         */
        internal fun IClashManager.queryMainProxyGroup(
            excludeNotSelectable: Boolean,
            sort: ProxySort,
        ): Pair<String, ProxyGroup>? {
            val names = queryProxyGroupNames(excludeNotSelectable)
            if (names.isEmpty()) return null

            val groupName = MainProxyGroupPolicy.resolveName(names) { name ->
                queryProxyGroup(name, sort).type == SELECTOR_TYPE
            } ?: return null

            return groupName to queryProxyGroup(groupName, sort)
        }
    }
}
