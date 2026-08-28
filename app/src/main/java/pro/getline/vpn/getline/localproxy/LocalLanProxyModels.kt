package pro.getline.vpn.getline.localproxy

/**
 * The product vocabulary of the local LAN proxy — the only local-proxy types
 * the UI is allowed to know (see plan Module boundary). Nothing here carries a
 * Session override, revision, reload result, Android `Network`, bind exception,
 * owner UUID or cleanup phase: those are runtime concerns and stay behind
 * [LocalLanProxy].
 */

/**
 * What the user configures and what is persisted. [password] is a secret: it
 * is masked in [toString] because these values end up in logs, crash reports
 * and debugger views by accident far more often than on purpose.
 */
data class LocalLanProxyUserConfig(
    val port: Int,
    val username: String,
    val password: String,
) {
    override fun toString(): String = "LocalLanProxyUserConfig(port=$port, username=$username, password=***)"
}

/** Whether Enable is offered at all, and when it is not, why. */
enum class LocalLanProxyAvailability {
    /** Not known yet — the first read of settings and runtime state is in flight. */
    Unknown,

    Ready,

    /** Settings can be edited, but there is no tunnel to attach a listener to. */
    VpnOffline,

    /** The encrypted settings store could not be opened; nothing can be enabled. */
    SettingsUnavailable,
}

sealed interface LocalLanProxyStatus {
    /** Before the first answer arrives; distinct from a known-disabled proxy. */
    object Loading : LocalLanProxyStatus

    object Disabled : LocalLanProxyStatus

    /** A transaction is in flight. Transitional, never persisted. */
    object Enabling : LocalLanProxyStatus

    object Disabling : LocalLanProxyStatus

    /** Bound and probed live. This is the endpoint a LAN client dials. */
    data class Active(val address: String, val port: Int) : LocalLanProxyStatus
}

/**
 * One coherent value: everything the screen renders comes from here, so it can
 * never show a stale address next to a fresh status.
 */
data class LocalLanProxySnapshot(
    val status: LocalLanProxyStatus = LocalLanProxyStatus.Loading,
    val availability: LocalLanProxyAvailability = LocalLanProxyAvailability.Unknown,
    /** Null only while loading, or when the settings store is unusable. */
    val config: LocalLanProxyUserConfig? = null,
)

/** Product outcomes. Deliberately closed, and deliberately free of detail that would only describe the runtime. */
sealed interface LocalLanProxyResult {
    object Success : LocalLanProxyResult

    /** No VPN session is running, so there is nothing to attach a listener to. */
    object VpnUnavailable : LocalLanProxyResult

    /** No single eligible Wi-Fi/hotspot IPv4 to bind — including "more than one", which is ambiguity. */
    object NoEligibleLan : LocalLanProxyResult

    data class InvalidSettings(val field: Field) : LocalLanProxyResult {
        enum class Field { Port, Username, Password }
    }

    /** Something else already answers on that port. GetLine did not stop it and did not stop the VPN. */
    object PortOccupied : LocalLanProxyResult

    /** Settings are locked while the proxy is active: the running listener's credentials cannot be hot-edited. */
    object ActiveNotEditable : LocalLanProxyResult

    /** The encrypted settings store could not be opened or written. */
    object SettingsUnavailable : LocalLanProxyResult

    /** The listener did not come up, and the failed attempt was rolled back. */
    object ApplyFailed : LocalLanProxyResult

    /**
     * The runtime could not account for a listener it had bound and stopped the
     * VPN rather than leave it reachable. The user sees a stopped VPN, so this
     * is worth saying plainly rather than folding into [ApplyFailed].
     */
    object SafetyStop : LocalLanProxyResult
}

/**
 * The product-facing API. Everything the Settings screen may call, and nothing
 * more: no AIDL, no `StatusProvider`, no transport broadcast, no override.
 */
interface LocalLanProxy {
    val state: kotlinx.coroutines.flow.StateFlow<LocalLanProxySnapshot>

    /**
     * Persists edited settings. Separate from [enable] because the screen lets
     * the user prepare port/credentials while the VPN is off.
     */
    suspend fun updateConfig(config: LocalLanProxyUserConfig): LocalLanProxyResult

    suspend fun enable(): LocalLanProxyResult

    suspend fun disable(): LocalLanProxyResult
}

/**
 * Product-level validation, mirroring what the Go processor accepts (see
 * `core/src/main/golang/native/config/local_proxy.go`). It exists here so an
 * invalid value is a sentence on screen instead of a listener that silently
 * never appears; the native side still fails closed on its own.
 */
object LocalLanProxyConfigValidator {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MAX_CREDENTIAL_LENGTH = 128

    fun validate(config: LocalLanProxyUserConfig): LocalLanProxyResult.InvalidSettings? = when {
        config.port !in MIN_PORT..MAX_PORT ->
            LocalLanProxyResult.InvalidSettings(LocalLanProxyResult.InvalidSettings.Field.Port)
        // ':' additionally excluded: Mihomo's mixed listener splits HTTP Basic
        // credentials at the first colon, so a username containing one could
        // authenticate over SOCKS5 and never over HTTP.
        !isSafeAscii(config.username) || config.username.contains(':') ->
            LocalLanProxyResult.InvalidSettings(LocalLanProxyResult.InvalidSettings.Field.Username)
        !isSafeAscii(config.password) ->
            LocalLanProxyResult.InvalidSettings(LocalLanProxyResult.InvalidSettings.Field.Password)
        else -> null
    }

    /** Non-empty, bounded, printable ASCII with no space or control byte. */
    private fun isSafeAscii(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= MAX_CREDENTIAL_LENGTH &&
            value.all { it.code in 0x21..0x7E }
}
