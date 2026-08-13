package pro.getline.vpn.getline.servers

/**
 * Project a persisted [ServerCatalog] onto the same main-group result the
 * live Mihomo adapter returns. No YAML, no Clash types.
 */
object ServerCatalogProjection {
    private const val MAX_RESOLVE_DEPTH = 8

    fun toLoadResult(
        catalog: ServerCatalog,
        excludeNotSelectable: Boolean,
        selections: Map<String, String> = emptyMap(),
        sortByTitle: Boolean = false,
    ): VpnServerLoadResult {
        if (catalog.effectiveMode() == ServerCatalog.MODE_DIRECT) {
            return VpnServerLoadResult.Empty
        }

        val group = resolveMainGroup(catalog, excludeNotSelectable)
            ?: return VpnServerLoadResult.Empty
        if (group.proxies.isEmpty()) return VpnServerLoadResult.Empty

        val selected = effectiveNow(group, selections)
        val servers = group.proxies.map { proxy ->
            VpnServerItem(
                name = proxy.name,
                displayName = proxy.name,
                delayMs = null,
                protocol = proxy.type.takeIf { it.isNotBlank() },
                isGroup = proxy.group,
                resolvedName = if (proxy.group) {
                    resolveLeafName(proxy.name, catalog, selections)
                } else {
                    null
                },
            )
        }

        return VpnServerLoadResult.Success(
            groupName = group.name,
            servers = if (sortByTitle) {
                servers.sortedBy { it.name }
            } else {
                servers
            },
            selectedName = selected,
            selectable = group.type == ServerCatalog.SELECTOR_TYPE,
        )
    }

    fun toMainSelection(
        catalog: ServerCatalog,
        excludeNotSelectable: Boolean,
        selections: Map<String, String> = emptyMap(),
        sortByTitle: Boolean = false,
    ): VpnMainSelection? {
        val result = toLoadResult(catalog, excludeNotSelectable, selections, sortByTitle)
        val success = result as? VpnServerLoadResult.Success ?: return null
        val selected = success.selectedName.takeIf { it.isNotBlank() } ?: return null
        val selectedIsGroup = success.servers.firstOrNull { it.name == selected }?.isGroup == true
        return VpnMainSelection(
            selectedName = selected,
            resolvedName = if (selectedIsGroup) {
                resolveLeafName(selected, catalog, selections)
            } else {
                null
            },
        )
    }

    fun contains(catalog: ServerCatalog, groupName: String, serverName: String): Boolean {
        val group = catalog.group(groupName) ?: return false
        return group.proxies.any { it.name == serverName }
    }

    internal fun resolveLeafName(
        startGroupName: String,
        catalog: ServerCatalog,
        selections: Map<String, String> = emptyMap(),
    ): String? {
        val visited = mutableSetOf<String>()
        var current = startGroupName

        repeat(MAX_RESOLVE_DEPTH) {
            if (!visited.add(current)) return null
            val group = catalog.group(current) ?: return null
            val now = effectiveNow(group, selections).takeIf { it.isNotBlank() } ?: return null
            val child = group.proxies.firstOrNull { it.name == now }
            if (child == null || !child.group) return now
            current = now
        }
        return null
    }

    private fun resolveMainGroup(
        catalog: ServerCatalog,
        excludeNotSelectable: Boolean,
    ): ServerCatalog.Group? {
        if (catalog.effectiveMode() == ServerCatalog.MODE_GLOBAL) {
            catalog.group(ServerCatalog.GLOBAL_GROUP)
                ?.takeIf { !it.hidden && it.name.isNotBlank() }
                ?.let { return it }
        }

        val visible = visibleYamlGroups(catalog, excludeNotSelectable)
        if (visible.isEmpty()) return null
        val groupName = MainProxyGroupPolicy.resolveName(visible.map { it.name }) {
            catalog.group(it)?.type == ServerCatalog.SELECTOR_TYPE
        } ?: return null
        return catalog.group(groupName)
    }

    private fun visibleYamlGroups(
        catalog: ServerCatalog,
        excludeNotSelectable: Boolean,
    ): List<ServerCatalog.Group> {
        return catalog.groups.filter { group ->
            group.name != ServerCatalog.GLOBAL_GROUP &&
                !group.hidden &&
                group.name.isNotBlank() &&
                (!excludeNotSelectable || group.type == ServerCatalog.SELECTOR_TYPE)
        }
    }

    private fun effectiveNow(
        group: ServerCatalog.Group,
        selections: Map<String, String>,
    ): String {
        val override = selections[group.name]
        return override?.takeIf { name ->
            name.isNotBlank() && group.proxies.any { it.name == name }
        } ?: group.now
    }

    private fun ServerCatalog.effectiveMode(): String {
        return when (mode.lowercase()) {
            ServerCatalog.MODE_DIRECT, ServerCatalog.MODE_GLOBAL -> mode.lowercase()
            else -> ServerCatalog.MODE_RULE
        }
    }

    private fun ServerCatalog.group(name: String): ServerCatalog.Group? =
        groups.firstOrNull { it.name == name }
}
