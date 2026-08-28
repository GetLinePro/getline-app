package com.github.kr328.clash.remote

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.localproxy.LocalLanProxyRuntimeState

class StatusClient(private val context: Context) {
    private val uri: Uri
        get() {
            return Uri.Builder()
                .scheme("content")
                .authority(Authorities.STATUS_PROVIDER)
                .build()
        }

    fun currentProfile(): String? {
        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )

            result?.getString("name")
        } catch (e: Exception) {
            Log.w("Query current profile: $e", e)

            null
        }
    }

    /**
     * The running session's local-proxy projection. Only the local-proxy
     * facade calls this: it is a transport, not a second product API (see plan
     * Module boundary).
     *
     * A failed call reads as inactive, which is the same thing an absent
     * service reports — there is no session to own a listener either way.
     */
    fun localLanProxyState(): LocalLanProxyRuntimeState {
        return try {
            LocalLanProxyRuntimeState.fromBundle(
                context.contentResolver.call(
                    uri,
                    StatusProvider.METHOD_LOCAL_LAN_PROXY_STATE,
                    null,
                    null
                )
            )
        } catch (e: Exception) {
            Log.w("Query local proxy state: $e", e)

            LocalLanProxyRuntimeState.Inactive
        }
    }
}