package com.github.kr328.clash.service.localproxy

import android.app.Service
import android.net.TetheringInterface
import android.net.TetheringManager
import androidx.annotation.RequiresApi
import com.github.kr328.clash.common.log.Log

/**
 * Reports which Wi-Fi tethered interfaces the system currently has up.
 *
 * Deliberately the *only* place that names a `Tethering*` type. Those classes
 * became public API in 36 and do not exist in the public SDK below it, so
 * every reference is quarantined here and this class is instantiated solely
 * behind an SDK check — that keeps class verification on older devices from
 * ever touching them.
 *
 * The callback is authoritative but coarse: the device spike showed it firing
 * on hotspot start and stop (`{} -> {wlan2} -> {}`) and nothing in between, so
 * it establishes *identity and existence* only. Detecting an address or
 * network change while the tethered set stays `{wlan2}` is
 * [LocalLanProxyEndpointMonitor]'s poll, not this observer's job.
 */
/**
 * The tether facts [LocalLanProxyEndpointMonitor] consumes, stated without any
 * version-gated type.
 *
 * The monitor holds this interface rather than the implementation so that no
 * call site of its own requires API 36; the single version-gated reference in
 * the codebase is the guarded construction of [LocalLanProxyTetherObserver].
 */
internal interface LocalLanProxyTetherSource : AutoCloseable {
    /** System-reported names of the currently up Wi-Fi tethered interfaces. */
    val tetheredWifiInterfaces: Set<String>

    fun start()
}

@RequiresApi(36)
internal class LocalLanProxyTetherObserver(
    service: Service,
    private val onChanged: (Set<String>) -> Unit,
) : LocalLanProxyTetherSource {
    private val tethering = service.getSystemService(TetheringManager::class.java)
    private val executor = service.mainExecutor

    @Volatile
    override var tetheredWifiInterfaces: Set<String> = emptySet()
        private set

    private var registered = false

    private val callback = object : TetheringManager.TetheringEventCallback {
        override fun onTetheredInterfacesChanged(interfaces: MutableSet<TetheringInterface>) {
            val names = interfaces
                .filter { it.type == TetheringManager.TETHERING_WIFI }
                .map { it.getInterface() }
                .toSet()

            if (names == tetheredWifiInterfaces) return

            tetheredWifiInterfaces = names

            Log.d("LocalLanProxy tethered wifi interfaces: $names")

            onChanged(names)
        }
    }

    override fun start() {
        if (registered || tethering == null) return

        try {
            tethering.registerTetheringEventCallback(executor, callback)
            registered = true
        } catch (e: Exception) {
            // Without this signal there is no trustworthy hotspot identity, so
            // hotspot simply stays unavailable rather than being guessed at.
            Log.w("LocalLanProxy tether observer register failed", e)
        }
    }

    override fun close() {
        tetheredWifiInterfaces = emptySet()

        if (!registered) return
        registered = false

        try {
            tethering?.unregisterTetheringEventCallback(callback)
        } catch (e: Exception) {
            Log.w("LocalLanProxy tether observer unregister failed", e)
        }
    }
}
