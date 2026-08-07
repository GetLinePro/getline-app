package com.github.kr328.clash.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.unbindServiceSilent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<IRemoteService>()

    /**
     * Last [bind] attempt returned false / threw. Cleared on accept or
     * [onServiceConnected]. Profile path re-calls [bind] on Retry; Advanced
     * [get] keeps waiting and never throws from this flag alone.
     */
    private val bindRejected = AtomicBoolean(false)

    fun wasBindRejected(): Boolean = bindRejected.get()

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            bindRejected.set(false)
            remote.set(service.unwrap(IRemoteService::class))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote.set(null)

            if (System.currentTimeMillis() - lastCrashed < TOGGLE_CRASHED_INTERVAL) {
                unbind()

                crashed()
            }

            lastCrashed = System.currentTimeMillis()
            // Shareable prefix for GL-19. Not a bind attempt — runtime disconnect.
            Log.w("profile_backend op=connection outcome=unavailable kind=disconnected")
        }
    }

    fun bind() {
        try {
            val accepted = context.bindService(
                RemoteService::class.intent,
                connection,
                Context.BIND_AUTO_CREATE,
            )
            // MIUI "process is bad" returns false — no onServiceConnected, no exception.
            // Do not call crashed() (wrong product surface). Android still requires
            // unbindService after a false return (registered connection).
            if (!accepted) {
                bindRejected.set(true)
                Log.w("profile_backend op=bind outcome=unavailable kind=bind_rejected")
                remote.reject(IllegalStateException("bind_rejected"))
                // Documented: false still registers the connection — must unbind.
                unbind()
            } else {
                // Connecting (or already up). Previous reject is no longer definitive;
                // waiters may park on get() until onServiceConnected.
                bindRejected.set(false)
            }
        } catch (e: Exception) {
            bindRejected.set(true)
            Log.w(
                "profile_backend op=bind outcome=unavailable kind=bind_exception " +
                    "ex=${e.javaClass.simpleName}",
            )
            remote.reject(IllegalStateException("bind_exception", e))
            unbind()

            crashed()
        }
    }

    fun unbind() {
        context.unbindServiceSilent(connection)

        remote.set(null)
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)
    }
}
