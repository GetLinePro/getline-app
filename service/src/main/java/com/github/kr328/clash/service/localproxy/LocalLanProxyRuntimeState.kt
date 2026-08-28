package com.github.kr328.clash.service.localproxy

import android.os.Bundle
import com.github.kr328.clash.common.log.Log

/**
 * What the running session is willing to say about the local proxy, and the
 * only local-proxy shape that crosses a process boundary as *state*.
 *
 * It is a projection, not a second copy of the truth: the coordinator's
 * transaction state is the truth, this mirrors it, and nothing here is
 * persisted or reconstructed after the session that produced it is gone (see
 * plan step 7). Credentials are deliberately absent — the facade reads those
 * from its own encrypted store, and there is no reason for a password to
 * travel through a `ContentProvider` call.
 */
sealed interface LocalLanProxyRuntimeState {
    /** No listener is bound, or no session is running to bind one. */
    object Inactive : LocalLanProxyRuntimeState

    /** A listener is bound to [address]`:`[port] and probed live. */
    data class Active(val address: String, val port: Int) : LocalLanProxyRuntimeState

    fun toBundle(): Bundle = Bundle().apply {
        when (val state = this@LocalLanProxyRuntimeState) {
            Inactive -> putBoolean(KEY_ACTIVE, false)
            is Active -> {
                putBoolean(KEY_ACTIVE, true)
                putString(KEY_ADDRESS, state.address)
                putInt(KEY_PORT, state.port)
            }
        }
    }

    companion object {
        private const val KEY_ACTIVE = "active"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PORT = "port"

        /**
         * Decodes what [toBundle] wrote. A null bundle means the provider had
         * nothing to answer with — no session, or the call failed — which is
         * the same observable situation as inactive.
         *
         * A malformed active payload is a bug on the writing side rather than
         * a state, and it is reported as [Inactive]: the alternative is
         * handing the UI an address or port it would then offer to copy. The
         * facade can still disable an unreported listener, because disabling
         * goes through the runtime binder and not through this projection.
         */
        fun fromBundle(bundle: Bundle?): LocalLanProxyRuntimeState {
            if (bundle == null) return Inactive
            if (!bundle.getBoolean(KEY_ACTIVE, false)) return Inactive

            val address = bundle.getString(KEY_ADDRESS)
            val port = bundle.getInt(KEY_PORT, 0)
            if (address.isNullOrEmpty() || port !in 1..65535) {
                Log.w("LocalLanProxy runtime state reported active without a usable endpoint")

                return Inactive
            }

            return Active(address, port)
        }
    }
}
