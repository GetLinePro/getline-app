package pro.getline.vpn.getline.localproxy

/**
 * Everything the facade needs from the runtime side, behind one seam so the
 * facade's own logic can be tested without a bound Binder or a
 * `ContentProvider`.
 *
 * The CMFA adapter owns service/AIDL types; product code above
 * [LocalLanProxyFacade] never sees this private runtime vocabulary.
 */
internal interface LocalLanProxyRuntimeClient {
    suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeOutcome
    suspend fun disable(): LocalLanProxyRuntimeOutcome

    /** The running session's projection, or inactive when there is no session. */
    suspend fun state(): LocalLanProxyRuntimeState
}

/** Product-side projection of the runtime adapter's state. */
internal sealed interface LocalLanProxyRuntimeState {
    object Inactive : LocalLanProxyRuntimeState

    data class Active(val address: String, val port: Int) : LocalLanProxyRuntimeState
}

/** Product-side result of one command accepted by the runtime adapter. */
internal data class LocalLanProxyRuntimeResult(
    val status: Status,
    val message: String? = null,
) {
    enum class Status {
        Enabled,
        Disabled,
        VpnUnavailable,
        NoEligibleEndpoint,
        PortOccupied,
        ApplyFailed,
        SafetyStop,
    }
}

/**
 * What a command attempt produced. The distinction is not cosmetic: a runtime
 * that *answered* `VpnUnavailable` has told us there is no session and so no
 * listener, while a call that never arrived has told us nothing at all. Both
 * look the same to the user — Enable is not available — but only the first is
 * evidence that a departed owner's listener is gone, which is what
 * `reconcileOwner()` needs before discarding their credentials.
 *
 * It stays internal: the product API keeps one `VpnUnavailable`.
 */
internal sealed interface LocalLanProxyRuntimeOutcome {
    /** The runtime ran the command and this is what it said. */
    data class Answered(val result: LocalLanProxyRuntimeResult) : LocalLanProxyRuntimeOutcome

    /** The command could not be delivered: not bound, bind rejected, or the service died mid-call. */
    object TransportUnavailable : LocalLanProxyRuntimeOutcome
}
