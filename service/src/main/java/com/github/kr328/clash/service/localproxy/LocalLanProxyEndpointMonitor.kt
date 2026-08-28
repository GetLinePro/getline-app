package com.github.kr328.clash.service.localproxy

import android.app.Service
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes which LAN IPv4 address, if any, the local proxy may bind to.
 *
 * This is the local-proxy module's own `ConnectivityManager` consumer, not a
 * branch in `NetworkObserveModule` — that module stays a general
 * core-networking concern with no local-proxy special case (see plan
 * Decisions). Nothing here decides *policy*: it reports the eligible endpoint
 * and signals change, and [LocalLanProxyRuntimeCoordinator] decides what a
 * change means for a running transaction.
 *
 * ## Two sources, because one framework contract does not cover both
 *
 * **Wi-Fi membership comes exclusively from the registered callback**, never
 * from a separate re-derived predicate. That matters for safety, not tidiness:
 * an endpoint selected by a filter *wider* than the request would never
 * receive the loss/change signals the request generates, so its disappearance
 * would go unnoticed while a credentialed listener stayed bound to it.
 * `NetworkRequest` also carries default capabilities of its own
 * (`NOT_RESTRICTED`, `TRUSTED`, and further ones added in later API levels), so
 * any hand-written mirror of that predicate is a standing invitation to drift.
 *
 * **Hotspot cannot use that mechanism**, and the device spike is what
 * establishes this rather than an assumption. A clean production build, with
 * the hotspot already up, did not receive the tethered network through this
 * monitor's request; a request written as `clearCapabilities()` plus
 * `NET_CAPABILITY_LOCAL_NETWORK` with no transport constraint received no
 * callbacks at all, while `dumpsys connectivity` showed the local-network
 * agent present with zero matching requests. So for hotspot the identity comes
 * from [LocalLanProxyTetherObserver] — a system-supplied interface name, not a
 * guess — joined to a live network snapshot by exact interface name.
 *
 * That join has no loss signal behind it, which is precisely what the poll
 * below supplies. It is not a general fallback: it runs only while a Wi-Fi
 * tether is actually up, it is bounded to that window, and it emits only on an
 * observed change. Running an access point costs incomparably more power than
 * one network snapshot every [POLL_INTERVAL_MS] milliseconds.
 *
 * ## Losses are recorded, not merely signalled
 *
 * [changes] is conflated and carries no payload, so a consumer only ever sees
 * the state at the moment it reads. That is safe for a level-triggered value
 * but not for an *event*: a hotspot stop immediately followed by a start
 * reuses the same interface name and IPv4 address, so by read time the only
 * visible difference is a fresh `Network` — which is also what a harmless
 * Wi-Fi reconnect looks like. Reading alone therefore cannot distinguish a
 * live listener from one whose address was pulled out from under it.
 *
 * So every disappearance is *recorded* when it is observed, as part of the
 * same [LocalLanProxyObservation] that describes what is currently there, and
 * reported as part of the endpoint identity. The coordinator compares epochs
 * and disables when one moved, rather than relying on being scheduled in time
 * to catch a transient null. Keeping the count in that value rather than
 * beside it is what makes an endpoint a function of a single read.
 *
 * ## Hotspot below API 36
 *
 * Unsupported, and endpoints there are never reported. `TetheringManager`'s
 * callback is not public API below 36, and the spike found no other stable
 * public signal tying an interface name to Settings tethering on API 34 — only
 * privileged `dumpsys`. Enable therefore refuses with no eligible endpoint
 * rather than binding a credentialed listener to a guessed interface.
 */
class LocalLanProxyEndpointMonitor(service: Service) : LocalLanProxyEndpointSource, AutoCloseable {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Signal-only, conflated: a burst of callbacks during a reconnect should
    // wake the coordinator once, and it re-reads state itself rather than
    // trusting anything carried in the event.
    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = signals

    /**
     * Exactly the networks currently satisfying [request]. The framework
     * delivers `onLost` when a network stops matching, so this set never
     * outgrows what the callback reports on.
     */
    private val matching: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())

    @Volatile
    private var started = false

    @Volatile
    private var wifiRegistered = false

    private val tetherObserver: LocalLanProxyTetherSource? =
        if (Build.VERSION.SDK_INT >= 36) {
            LocalLanProxyTetherObserver(service, ::onTetheredInterfacesChanged)
        } else {
            null
        }

    private val pollLock = Any()
    private var pollJob: Job? = null

    /**
     * Attribution from the most recent observation, so a loss can be recorded
     * on a callback thread without a blocking query. It may lag reality by up
     * to one poll interval, which is harmless: an address acquired and lost
     * inside that window was never reported to anyone, so nothing was ever
     * approved on it. The approved address is always present here, because
     * approving it required reading it.
     *
     * Replaced only through [updateAttribution] or [commitObservation], never
     * assigned directly, so that no path can overwrite the record of what used
     * to be there without first booking what went missing — and so that a
     * recompute whose query was overtaken by a callback is refused instead of
     * reinstating what the callback removed.
     */
    private val observed = LocalLanProxyObservedState(LocalLanProxyObservation<Network>())

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            matching.add(network)
            signal("available")
        }

        override fun onLost(network: Network) {
            matching.remove(network)

            // Before signalling, so that whoever the signal wakes already sees
            // the discontinuity. This also closes the same race on Wi-Fi that
            // the tethered path has: a reconnect keeping the DHCP lease
            // otherwise looks identical to a network that never dropped.
            updateObservation("wifi-lost") {
                it.observing(it.attribution.copy(byNetwork = it.attribution.byNetwork - network))
            }

            signal("lost")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            matching.add(network)

            // An address can be dropped, or swapped, without the network ever
            // being lost. Collapsed to a single read, A -> B -> A is
            // indistinguishable from A that never moved, so the addresses this
            // network no longer carries are booked here. The properties are
            // handed to us, so this costs no query.
            updateObservation("wifi-link-properties") {
                it.observing(
                    it.attribution.copy(
                        byNetwork = it.attribution.byNetwork +
                            (network to eligibleAddresses(linkProperties)),
                    ),
                )
            }

            signal("link-properties")
        }
    }

    fun start() {
        if (started) return
        started = true

        observed.reset(LocalLanProxyObservation())

        try {
            connectivity.registerNetworkCallback(request, callback)
            wifiRegistered = true
        } catch (e: Exception) {
            // Without the callback the coordinator never learns about address
            // loss, so Wi-Fi candidates must stay unreported rather than let
            // enable proceed on an unwatched address. Hotspot is unaffected:
            // it carries its own observation below.
            Log.w("LocalLanProxy endpoint monitor register failed", e)
        }

        tetherObserver?.start()
    }

    override fun close() {
        if (!started) return
        started = false

        stopPoll()
        scope.cancel()

        tetherObserver?.close()

        if (wifiRegistered) {
            wifiRegistered = false

            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w("LocalLanProxy endpoint monitor unregister failed", e)
            }
        }

        matching.clear()

        // Reset, not an update: shutting down is not a loss to record, and
        // there is no transaction left to protect. It still advances the
        // revision, so a recompute in flight cannot commit across the close.
        observed.reset(LocalLanProxyObservation())
    }

    /**
     * Null whenever the monitor is not observing, or when the observed
     * candidates are anything other than exactly one eligible address.
     */
    override suspend fun currentEndpoint(): LocalLanProxyEndpoint? = computeEndpoint()

    /**
     * A tether start or stop is authoritative and immediate, so it always
     * signals. It also opens and closes the poll window: there is nothing to
     * poll for once the tethered set is empty.
     */
    private fun onTetheredInterfacesChanged(names: Set<String>) {
        if (names.isEmpty()) {
            // Forcibly, not by diff. The tether callback is authoritative about
            // the interface being down, and on an OEM that also delivers the
            // same (Network, address) through the Wi-Fi callback, the stale
            // copy left in byNetwork would otherwise keep the address looking
            // present until an onLost that may arrive after the hotspot has
            // already restarted. The address would then never be seen to
            // leave, and a dead listener would pass for a live one.
            //
            // The reverse is deliberately not symmetric: an onLost only says
            // the network stopped matching this monitor's request, which is no
            // statement about an interface the tether observer still reports.
            updateObservation("tether-stopped") {
                it.observing(
                    next = it.attribution.copy(tethered = emptyMap()),
                    forciblyLost = it.attribution.tetheredAddresses,
                )
            }
            stopPoll()
        } else {
            startPoll()
        }

        signal("tethering")
    }

    /**
     * The single way observed state changes, so that every disappearance is
     * booked exactly where it is noticed.
     *
     * This is deliberately not a plain setter. The poll's whole purpose is to
     * catch an address vanishing under an otherwise unchanged tether, and it
     * finds that out by recomputing the observation — which is the same act
     * that would discard the previous one. Diffing before the swap means the
     * discovery cannot destroy its own evidence.
     */
    private fun updateObservation(
        reason: String,
        transform: (LocalLanProxyObservation<Network>) -> LocalLanProxyObservation<Network>,
    ) {
        val (previous, updated) = observed.update(transform)

        logVanished(previous, updated, reason)
    }

    /**
     * Publishes a recomputed snapshot, but only if no callback has changed the
     * observed state since the query behind it began. A refused commit is not
     * an error: the callback that won had the newer truth and has already
     * booked whatever it saw disappear.
     */
    private fun commitObservation(
        revision: Long,
        snapshot: LocalLanProxyAttribution<Network>,
    ): Boolean {
        val (previous, updated) = observed.commit(revision) { it.observing(snapshot) }
            ?: return false


        logVanished(previous, updated, "observation")

        return true
    }

    /**
     * Reporting only. The counts themselves moved with the swap that published
     * them; nothing here can change what was recorded.
     *
     * Read from the counts rather than by re-diffing attribution, because a
     * forced loss need not show up in that diff at all — a Wi-Fi duplicate of
     * a stopped tether address masks it, which is the very case this logging
     * is most needed for.
     */
    private fun logVanished(
        previous: LocalLanProxyObservation<Network>,
        updated: LocalLanProxyObservation<Network>,
        reason: String,
    ) {
        val vanished = updated.epochs
            .filterKeys { updated.epochOf(it) != previous.epochOf(it) }
            .keys

        if (vanished.isEmpty()) return

        Log.d("LocalLanProxy endpoint loss recorded ($reason): $vanished")
    }

    private fun eligibleAddresses(link: LinkProperties): Set<Inet4Address> =
        link.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .filterTo(mutableSetOf(), LocalLanProxyEndpointPolicy::isEligibleListenAddress)

    private fun startPoll() {
        synchronized(pollLock) {
            if (!started || pollJob?.isActive == true) return

            pollJob = scope.launch {
                // The tether event that opened this window has already
                // signalled, so the first sample only establishes the baseline
                // that later samples are compared against. That baseline may
                // legitimately be null: the spike did not establish whether the
                // tethered network is visible by the time the callback arrives,
                // so a startup race resolves as a later null -> endpoint edge.
                val gate = LocalLanProxyChangeGate<LocalLanProxyEndpoint>()

                while (isActive) {
                    try {
                        if (gate.observe(computeEndpoint())) {
                            signal("tether-poll")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // A failed system query is not evidence of change, so
                        // the gate is deliberately left un-updated rather than
                        // fed a null that would read as a loss. But the sample
                        // is unknown, and a poll that dies here would leave an
                        // active listener unwatched until the next tether
                        // event — the very gap this loop exists to close. So
                        // signal and keep going: the coordinator re-reads
                        // through its own boundary, which fail-stops if it
                        // cannot account for the listener either, and does
                        // nothing at all when no transaction is active.
                        Log.w("LocalLanProxy tether poll sample failed", e)

                        signal("tether-poll-error")
                    }

                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopPoll() {
        synchronized(pollLock) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun computeEndpoint(): LocalLanProxyEndpoint? {
        if (!started) return null

        repeat(OBSERVATION_ATTEMPTS) {
            // Read the revision *before* querying: the query is the window in
            // which a callback can overtake us.
            val revision = observed.revision

            if (commitObservation(revision, query())) return select(observed.current)

            Log.d("LocalLanProxy observation overtaken by a callback; re-querying")
        }

        // Answered from the committed state either way, never from a query the
        // guard rejected. A refused commit means a callback held newer truth,
        // and reporting our declined snapshot instead would be worse than
        // stale: pairing an address with the `Network` it used to be on hands
        // the coordinator a dead probe route, which is how a teardown becomes
        // unconfirmable and fail-stops the VPN.
        return select(observed.current)
    }

    private fun query(): LocalLanProxyAttribution<Network> {
        val wifi = wifiCandidates()
        val tethered = tetheredCandidates()

        return LocalLanProxyAttribution(
            byNetwork = wifi.groupBy({ it.network }, { it.address })
                .mapValues { (_, addresses) -> addresses.toSet() },
            tethered = tethered.groupBy({ it.network }, { it.address })
                .mapValues { (_, addresses) -> addresses.toSet() },
        )
    }

    /**
     * Takes the whole observation, never its parts. Address, network and epoch
     * come from one immutable value, so there is no window between reading the
     * route and reading the epoch for a callback to slip into and produce a
     * pairing that never existed.
     */
    private fun select(observation: LocalLanProxyObservation<Network>): LocalLanProxyEndpoint? {
        val selected = observation.selected()
            ?: return null

        return LocalLanProxyEndpoint(
            address = selected.address,
            network = selected.network,
            epoch = selected.epoch,
        )
    }

    private fun wifiCandidates(): List<ObservedEndpoint<Network>> {
        if (!wifiRegistered) return emptyList()

        return matching.flatMap { network ->
            val link = connectivity.getLinkProperties(network)
                ?: return@flatMap emptyList<ObservedEndpoint<Network>>()

            link.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .filter(LocalLanProxyEndpointPolicy::isEligibleListenAddress)
                .map { ObservedEndpoint(network = network, address = it) }
        }
    }

    private fun tetheredCandidates(): List<ObservedEndpoint<Network>> {
        val names = tetherObserver?.tetheredWifiInterfaces ?: return emptyList()
        if (names.isEmpty()) return emptyList()

        return LocalLanProxyTetherReconciler.candidates(names, observedNetworks())
    }

    /**
     * The full current network list rather than callback-tracked membership,
     * because the tethered network demonstrably never reaches this monitor's
     * callback. `getAllNetworks()` is deprecated in favour of exactly that
     * callback, so its use is confined to the one case the callback does not
     * cover, and only while a tether is up.
     */
    @Suppress("DEPRECATION")
    private fun observedNetworks(): List<ObservedNetwork<Network>> =
        connectivity.allNetworks.mapNotNull { network ->
            val link = connectivity.getLinkProperties(network)
                ?: return@mapNotNull null

            ObservedNetwork(
                network = network,
                interfaceName = link.interfaceName,
                addresses = link.linkAddresses.mapNotNull { it.address as? Inet4Address },
            )
        }

    private fun signal(reason: String) {
        if (!started) return

        Log.d("LocalLanProxy endpoint monitor: $reason")

        signals.tryEmit(Unit)
    }

    private companion object {
        /**
         * Chosen to make an unnoticed address change short rather than
         * unbounded; the coordinator's own teardown transaction is what makes
         * it safe, not this interval.
         */
        private const val POLL_INTERVAL_MS = 2_000L

        /**
         * Callback updates are rare and each retry is only a couple of system
         * queries, so a small bound is enough to make losing every attempt
         * effectively impossible without risking an unbounded spin.
         */
        private const val OBSERVATION_ATTEMPTS = 3
    }
}
