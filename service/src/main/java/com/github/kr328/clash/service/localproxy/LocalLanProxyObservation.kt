package com.github.kr328.clash.service.localproxy

import java.net.Inet4Address

/**
 * One observed network, reduced to the three facts the tether join needs.
 *
 * Generic in [N] so the join can be exercised without `android.net.Network`,
 * which has no test double; the monitor instantiates it with the real type.
 */
internal data class ObservedNetwork<N>(
    val network: N,
    val interfaceName: String?,
    val addresses: List<Inet4Address>,
)

/** An observed network paired with one of its eligible addresses. */
internal data class ObservedEndpoint<N>(
    val network: N,
    val address: Inet4Address,
)

/**
 * Joins the system's tethered-interface report to the networks this process can
 * see, so a hotspot address can be identified without guessing.
 *
 * The device spike behind this (see plan Open questions) established the shape:
 * `TetheringEventCallback` names the tethered interface authoritatively
 * (`wlan2`), and the same interface appears as an app-visible network whose
 * [android.net.LinkProperties.getInterfaceName] is exactly that string. The
 * join is therefore an *exact* name match against a system-supplied name — it
 * is emphatically not the forbidden "guess an interface called wlanN" rule
 * that the API 34 result ruled out.
 *
 * Everything here fails closed: an empty tether set yields no candidates, a
 * network with no interface name never matches, and addresses still have to
 * pass [LocalLanProxyEndpointPolicy.isEligibleListenAddress].
 */
internal object LocalLanProxyTetherReconciler {
    fun <N> candidates(
        tetheredNames: Set<String>,
        networks: List<ObservedNetwork<N>>,
    ): List<ObservedEndpoint<N>> {
        if (tetheredNames.isEmpty()) return emptyList()

        return networks
            .filter { it.interfaceName != null && it.interfaceName in tetheredNames }
            .flatMap { observed ->
                observed.addresses
                    .filter(LocalLanProxyEndpointPolicy::isEligibleListenAddress)
                    .map { ObservedEndpoint(observed.network, it) }
            }
            .distinct()
    }
}

/**
 * Turns a repeatedly-sampled value into an edge signal.
 *
 * The tether poll re-reads the endpoint every couple of seconds, but the
 * coordinator must only be woken when something actually moved; signalling on
 * every tick would put a periodic wake-up on a service that is otherwise
 * event-driven. The first observation only establishes the baseline, because
 * the event that started the poll has already signalled on its own.
 */
internal class LocalLanProxyChangeGate<T> {
    private var primed = false
    private var last: T? = null

    fun observe(current: T?): Boolean {
        val changed = primed && current != last

        primed = true
        last = current

        return changed
    }
}

/**
 * Which addresses the monitor currently believes it is observing, and where
 * each came from.
 *
 * Kept so that a disappearance can be *recorded* from a framework callback
 * without a blocking query back to the system server — the callback threads
 * are the wrong place for one. Generic in [N] so the diff can be tested
 * without `android.net.Network`.
 *
 * Attribution is per source because a loss is per source: a Wi-Fi network
 * going away says nothing about an address the hotspot still holds.
 */
internal data class LocalLanProxyAttribution<N>(
    val byNetwork: Map<N, Set<Inet4Address>> = emptyMap(),
    val tethered: Map<N, Set<Inet4Address>> = emptyMap(),
) {
    val addresses: Set<Inet4Address>
        get() = endpoints.mapTo(mutableSetOf()) { it.address }

    /** Only the addresses seen on a tethered interface. */
    val tetheredAddresses: Set<Inet4Address>
        get() = tethered.values.flatMapTo(mutableSetOf()) { it }

    /**
     * Every observed (network, address) pair, deduplicated.
     *
     * This is also what the endpoint is selected from, and that is deliberate:
     * the committed attribution is the *only* description of what has been
     * observed. Answering a read from anything else — a query result the state
     * machine declined to commit, say — can pair a live address with a dead
     * network, and a route that no longer works is precisely what turns a
     * routine teardown into an unconfirmable one.
     *
     * The two sources are not provably disjoint: the device spike saw the
     * tethered network carry `TRANSPORT_WIFI`, and an OEM whose request
     * delivery differs could surface one network through both. Without the
     * deduplication that single endpoint would look like two candidates and be
     * refused as ambiguous.
     */
    val endpoints: List<ObservedEndpoint<N>>
        get() = (byNetwork.asSequence() + tethered.asSequence())
            .flatMap { (network, addresses) ->
                addresses.asSequence().map { ObservedEndpoint(network, it) }
            }
            .distinct()
            .toList()

    /**
     * Addresses present in [previous] and no longer present here.
     *
     * Every replacement of the observed state goes through this, which is the
     * point: an update that quietly overwrote the old attribution would erase
     * the only evidence that something disappeared, and the disappearance is
     * exactly what the ledger needs. An address that merely moved between
     * sources — or between networks — has not vanished and is not reported.
     */
    fun vanishedSince(previous: LocalLanProxyAttribution<N>): Set<Inet4Address> =
        previous.addresses - addresses
}

/**
 * One address selected for use, with the epoch it carried at the moment it was
 * selected.
 */
internal data class LocalLanProxySelectedEndpoint<N>(
    val network: N,
    val address: Inet4Address,
    val epoch: Long,
)

/**
 * Everything the monitor has observed, as one value.
 *
 * Attribution and the per-address loss counts live together deliberately. An
 * endpoint's identity is `(address, network, epoch)`, and assembling it from
 * two independently-read sources admits combinations that never existed: read
 * attribution, let a callback remove the address and bump its count, then read
 * the count, and the result is the old network wearing the new epoch. The
 * coordinator reads that as "the address came back", adopts a route that is
 * already dead, and its teardown probe can then find the address still local
 * but unreachable — the ambiguous case that fail-stops the VPN.
 *
 * Holding both here makes that unrepresentable rather than unlikely: every
 * read is of a single immutable value, so no barrier between two reads exists
 * to slip through.
 */
internal data class LocalLanProxyObservation<N>(
    val attribution: LocalLanProxyAttribution<N> = LocalLanProxyAttribution(),
    val epochs: Map<Inet4Address, Long> = emptyMap(),
) {
    fun epochOf(address: Inet4Address): Long = epochs[address] ?: 0L

    /**
     * Adopts [next] as the observed attribution, booking every address that
     * vanished in the same step.
     *
     * The booking cannot be a separate call. Replacing attribution is how a
     * disappearance is discovered *and* how the evidence for it is discarded,
     * so the count has to move with the swap or not at all.
     *
     * [forciblyLost] books addresses a source has declared gone even if they
     * survive the diff because another source still lists them.
     */
    fun observing(
        next: LocalLanProxyAttribution<N>,
        forciblyLost: Set<Inet4Address> = emptySet(),
    ): LocalLanProxyObservation<N> {
        // The diff alone is not enough when the sources are not disjoint. An
        // address the system has authoritatively declared gone can still be
        // listed by a slower source that has not caught up — the same
        // (network, address) sitting in Wi-Fi attribution because its callback
        // has yet to fire — and a flat comparison of address sets then sees
        // nothing leave. [forciblyLost] lets the authoritative source say so
        // outright, restricted to addresses actually being observed so it can
        // never invent a loss for something never seen.
        val vanished = next.vanishedSince(attribution) + (forciblyLost intersect attribution.addresses)

        if (vanished.isEmpty()) return copy(attribution = next)

        return LocalLanProxyObservation(
            attribution = next,
            epochs = epochs + vanished.associateWith { epochOf(it) + 1L },
        )
    }

    /**
     * The single eligible endpoint, or null when there is none — including
     * when there is more than one, which is ambiguity and fails closed.
     */
    fun selected(): LocalLanProxySelectedEndpoint<N>? {
        val endpoint = LocalLanProxyEndpointPolicy.selectSingle(attribution.endpoints)
            ?: return null

        return LocalLanProxySelectedEndpoint(
            network = endpoint.network,
            address = endpoint.address,
            epoch = epochOf(endpoint.address),
        )
    }
}

internal class LocalLanProxyObservedState<T : Any>(initial: T) {
    private val lock = Any()

    private var revisionValue = 0L
    private var value: T = initial

    val revision: Long
        get() = synchronized(lock) { revisionValue }

    /** The last committed value. Never a query result that was refused. */
    val current: T
        get() = synchronized(lock) { value }

    /** Applies [transform] unconditionally. Returns the value before and after. */
    fun update(transform: (T) -> T): Pair<T, T> = synchronized(lock) {
        val previous = value
        val updated = transform(previous)

        value = updated
        revisionValue++

        previous to updated
    }

    /**
     * Applies [transform] only if [expectedRevision] is still current. Returns
     * the value before and after on success, or null if the write was refused
     * as stale.
     *
     * A transform rather than a plain value, so that anything derived from the
     * previous state — the loss counts, above all — is computed under the same
     * lock that publishes it.
     */
    fun commit(expectedRevision: Long, transform: (T) -> T): Pair<T, T>? = synchronized(lock) {
        if (revisionValue != expectedRevision) return@synchronized null

        val previous = value
        val updated = transform(previous)

        value = updated
        revisionValue++

        previous to updated
    }

    /**
     * Discards the current value without treating the difference as an
     * observation. Advances the revision, so any recompute already in flight is
     * refused rather than resurrecting state across the reset.
     */
    fun reset(initial: T) {
        synchronized(lock) {
            value = initial
            revisionValue++
        }
    }
}
