package pro.getline.vpn.getline.servers

/**
 * One selectable entry from the main Mihomo proxy group.
 *
 * [name] is the patchSelector identity; [displayName] is shown as-is.
 * [delayMs] is the last measured latency, null when never tested.
 * [protocol] is the outbound adapter type the core reports (VLESS/HY2/TROJAN).
 * On a relay node ("Обход") that is the hop to the intermediate server, while
 * the name suffix names the tunnel carried over it — so the two describing
 * different things is normal, not a mismatch.
 * [isGroup] marks a nested group such as "⚡ Авто"; [resolvedName] is the leaf
 * that group currently points at, so the active node is not invisible.
 */
data class VpnServerItem(
    val name: String,
    val displayName: String,
    val delayMs: Int? = null,
    val protocol: String? = null,
    val isGroup: Boolean = false,
    val resolvedName: String? = null,
)

/**
 * Servers destination state. Owned by the Home shell Activity (not per-tab Views).
 * Does not own Clash lifecycle or latency tests.
 */
sealed interface VpnServerUiState {
    data object Loading : VpnServerUiState

    data class Ready(
        val groupName: String,
        val servers: List<VpnServerItem>,
        val selectedName: String,
        val selectable: Boolean,
    ) : VpnServerUiState

    data object Empty : VpnServerUiState

    /** Clash/core not running — list requires a live loaded profile (same gate as Proxy). */
    data object VpnStopped : VpnServerUiState

    data object Failed : VpnServerUiState
}

/** Outcome of loading the main selector group via withClash. */
sealed interface VpnServerLoadResult {
    data class Success(
        val groupName: String,
        val servers: List<VpnServerItem>,
        val selectedName: String,
        val selectable: Boolean,
    ) : VpnServerLoadResult

    data object Empty : VpnServerLoadResult
    data object Failed : VpnServerLoadResult
}
