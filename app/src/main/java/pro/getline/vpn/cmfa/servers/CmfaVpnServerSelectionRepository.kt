package pro.getline.vpn.cmfa.servers

import android.content.Context
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.store.UiStore
import pro.getline.vpn.getline.servers.MainProxyGroupPolicy
import pro.getline.vpn.getline.servers.ServerCatalog
import pro.getline.vpn.getline.servers.ServerCatalogProjection
import pro.getline.vpn.getline.servers.VpnMainSelection
import pro.getline.vpn.getline.servers.VpnServerItem
import pro.getline.vpn.getline.servers.VpnServerLoadResult
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Thin adapter over the same Clash APIs used by [com.github.kr328.clash.ProxyActivity].
 * Does not start latency tests or open the legacy Proxy UI.
 *
 * VPN on: live Mihomo. VPN off: [ServerCatalog] written at import/refresh.
 * Main-group selection is centralized in [MainProxyGroupPolicy] + [queryMainProxyGroup].
 * Reads [UiStore.proxySort] / [UiStore.proxyExcludeNotSelectable] so product call sites
 * never touch CMFA types.
 */
class CmfaVpnServerSelectionRepository(
    context: Context,
    private val importedRoot: File,
    private val vpnRunning: () -> Boolean,
) : VpnServerSelectionRepository {
    private val uiStore = UiStore(context.applicationContext)

    override suspend fun loadMainGroup(): VpnServerLoadResult {
        if (!vpnRunning()) {
            return loadMainGroupOffline()
        }
        val excludeNotSelectable = uiStore.proxyExcludeNotSelectable
        val sort = uiStore.proxySort
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

    override suspend fun queryMainSelection(): VpnMainSelection? {
        if (!vpnRunning()) {
            return queryMainSelectionOffline()
        }
        val excludeNotSelectable = uiStore.proxyExcludeNotSelectable
        val sort = uiStore.proxySort
        return try {
            withClash {
                val resolved = queryMainProxyGroup(excludeNotSelectable, sort)
                    ?: return@withClash null
                val group = resolved.second
                val now = group.now.takeIf { it.isNotBlank() } ?: return@withClash null
                val selectedIsGroup = group.proxies.firstOrNull { it.name == now }?.isGroup == true
                VpnMainSelection(
                    selectedName = now,
                    // Same descent [loadMainGroup] runs per entry, but only for the
                    // one Home names — a selected group otherwise hides its node.
                    // Scoped runCatching: a failed descent costs the leaf, not the
                    // selection, so Home degrades to "⚡ Авто", never to "unknown".
                    resolvedName = if (selectedIsGroup) {
                        runCatching {
                            resolveLeafName(now) { queryProxyGroup(it, sort) }
                        }.getOrNull()
                    } else {
                        null
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun healthCheckMainGroup(): Boolean {
        if (!vpnRunning()) return false
        val excludeNotSelectable = uiStore.proxyExcludeNotSelectable
        val sort = uiStore.proxySort
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

    override suspend fun select(groupName: String, serverName: String): Boolean {
        return try {
            if (vpnRunning()) {
                withClash {
                    patchSelector(groupName, serverName)
                }
            } else {
                val loaded = loadOfflineState() ?: return false
                if (!ServerCatalogProjection.contains(loaded.catalog, groupName, serverName)) {
                    return false
                }
                // #136: withProfile retries on DeadObject. setSelected is an
                // idempotent upsert, so a retry cannot create a second row.
                withProfile {
                    setSelected(loaded.uuid, groupName, serverName)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadMainGroupOffline(): VpnServerLoadResult {
        val loaded = loadOfflineState() ?: return VpnServerLoadResult.Empty
        return ServerCatalogProjection.toLoadResult(
            catalog = loaded.catalog,
            excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
            selections = loaded.selections,
            sortByTitle = uiStore.proxySort == ProxySort.Title,
        )
    }

    private suspend fun queryMainSelectionOffline(): VpnMainSelection? {
        val loaded = loadOfflineState() ?: return null
        return ServerCatalogProjection.toMainSelection(
            catalog = loaded.catalog,
            excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
            selections = loaded.selections,
            sortByTitle = uiStore.proxySort == ProxySort.Title,
        )
    }

    private suspend fun loadOfflineState(): OfflineState? {
        val snapshot = try {
            withProfile { queryActiveSelectionSnapshot() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } ?: return null
        val catalog = withContext(Dispatchers.IO) {
            ServerCatalog.read(importedRoot.resolve(snapshot.uuid.toString()))
        } ?: return null
        return OfflineState(
            uuid = snapshot.uuid,
            catalog = catalog,
            selections = snapshot.asMap(),
        )
    }

    private data class OfflineState(
        val uuid: UUID,
        val catalog: ServerCatalog,
        val selections: Map<String, String>,
    )

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
