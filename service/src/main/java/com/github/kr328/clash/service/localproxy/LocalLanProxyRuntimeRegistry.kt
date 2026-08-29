package com.github.kr328.clash.service.localproxy

import android.content.Context
import com.github.kr328.clash.service.util.sendLocalLanProxyChanged

/**
 * Service-lifetime home of [LocalLanProxyRuntimeState], in the `:background`
 * process that also hosts `TunService`, `RemoteService`'s binder and
 * `StatusProvider` — the same lightweight service-owned-state pattern
 * [LocalLanProxyRuntimeHolder] uses.
 *
 * Only [LocalLanProxyRuntimeCoordinator] writes here, and it writes exactly
 * the transitions of its own state machine, plus the session-boundary resets
 * in its `start`/`close`. `StatusProvider` and the change broadcast are a read
 * adapter and an invalidation signal over this value; neither keeps proxy
 * state of its own, so there is nothing that can disagree with the coordinator
 * (see plan step 7).
 *
 * [publish] is the only mutator on purpose: write-before-notify has to hold on
 * every path, so a receiver woken by the broadcast always reads the value that
 * caused it, and the ordering is not something each call site can get wrong.
 */
object LocalLanProxyRuntimeRegistry {
    @Volatile
    private var current: LocalLanProxyRuntimeState = LocalLanProxyRuntimeState.Inactive

    /** The last published projection. Never null: no session reads as inactive. */
    val state: LocalLanProxyRuntimeState
        get() = current

    /**
     * Writes [state], then invalidates readers. A repeat of the current value
     * is dropped rather than broadcast: it carries no information, and the
     * session-boundary resets would otherwise wake every reader on each VPN
     * start that never had a proxy.
     */
    fun publish(context: Context, state: LocalLanProxyRuntimeState) {
        if (current == state) return

        current = state

        context.sendLocalLanProxyChanged()
    }

    /** Session boundary: nothing is bound before a session starts or after it ends. */
    fun reset(context: Context) = publish(context, LocalLanProxyRuntimeState.Inactive)
}
