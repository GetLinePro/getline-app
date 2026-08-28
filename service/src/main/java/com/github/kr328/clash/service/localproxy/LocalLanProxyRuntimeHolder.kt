package com.github.kr328.clash.service.localproxy

/**
 * Process-local handle to the coordinator for whichever `TunService` runtime
 * session is currently alive, in the same `:background` process as
 * `RemoteService`'s AIDL binder — mirrors `StatusProvider`'s lightweight
 * service-owned-state pattern (see plan Current facts). Null whenever no VPN
 * session is running; the private AIDL adapter reports VpnUnavailable in
 * that case instead of reaching across processes for it.
 */
object LocalLanProxyRuntimeHolder {
    @Volatile
    var coordinator: LocalLanProxyRuntimeCoordinator? = null
}
