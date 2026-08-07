package com.github.kr328.clash.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Resource<T> {
    private interface Callback<T> {
        fun accept(value: T)
        fun reject(error: Throwable)
    }

    private val pending: MutableSet<Callback<T>> = mutableSetOf()

    private var value: T? = null

    suspend fun get(): T {
        return suspendCancellableCoroutine { ctx ->
            val callback = object : Callback<T> {
                override fun accept(value: T) {
                    ctx.resume(value)
                }

                override fun reject(error: Throwable) {
                    ctx.resumeWithException(error)
                }
            }

            ctx.invokeOnCancellation {
                cancel(callback)
            }

            get(callback)
        }
    }

    /** Non-suspending snapshot for bind-reject fail-fast without parking [get]. */
    @Synchronized
    fun peek(): T? = value

    fun set(v: T?) {
        setAndNotify(v)
    }

    /**
     * Fail **current** [get] waiters only. Does **not** sticky-reject future [get]
     * (Advanced/Proxy/withClash must keep waiting for a later connect, not crash).
     * Profile fail-fast lives in [com.github.kr328.clash.util.withProfile] via
     * [Service.bindRejected] + rebind.
     */
    fun reject(error: Throwable) {
        rejectPending(error)
    }

    fun reset(v: T) {
        resetIfMatched(v)
    }

    @Synchronized
    private fun get(callback: Callback<T>) {
        val v = value

        if (v == null) {
            pending.add(callback)
        } else {
            callback.accept(v)
        }
    }

    @Synchronized
    private fun setAndNotify(value: T?) {
        this.value = value

        if (value != null) {
            pending.forEach {
                it.accept(value)
            }

            pending.clear()
        }
    }

    @Synchronized
    private fun rejectPending(error: Throwable) {
        if (value != null) return
        pending.forEach {
            it.reject(error)
        }
        pending.clear()
    }

    @Synchronized
    private fun resetIfMatched(value: T) {
        if (this.value === value) {
            this.value = null
        }
    }

    @Synchronized
    private fun cancel(callback: Callback<T>) {
        pending.remove(callback)
    }
}
