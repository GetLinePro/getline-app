package com.github.kr328.clash.service.clash.module

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class ConfigurationReloadTimeoutException(timeoutMs: Long) :
    IOException("Configuration reload did not complete within ${timeoutMs}ms")

/**
 * Bounded, correlated mailbox for direct configuration reload requests.
 *
 * A rendezvous channel adds no unbounded request buffer, [serial] permits only
 * one direct request to be in flight, and the timeout covers queueing plus the
 * matching reload result. [ConfigurationModule]'s single select loop still
 * serializes the accepted request with broadcast-triggered reloads.
 */
internal class ConfigurationReloadMailbox(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ConfigurationReloadPort {
    internal class Request {
        private val completion = CompletableDeferred<ConfigurationReloadResult>()

        suspend fun await(): ConfigurationReloadResult = completion.await()

        fun complete(result: ConfigurationReloadResult) {
            completion.complete(result)
        }

        fun fail(cause: Throwable) {
            completion.completeExceptionally(cause)
        }
    }

    private val serial = Mutex()
    private val requests = Channel<Request>(Channel.RENDEZVOUS)
    private val active = ConcurrentHashMap<Request, Unit>()

    val onReceive: SelectClause1<Request>
        get() = requests.onReceive

    override suspend fun reloadAndAwait(): ConfigurationReloadResult {
        val result = withTimeoutOrNull(timeoutMs) {
            serial.withLock {
                val request = Request()
                active[request] = Unit
                try {
                    requests.send(request)
                    request.await()
                } finally {
                    active.remove(request)
                }
            }
        }
        return result ?: throw ConfigurationReloadTimeoutException(timeoutMs)
    }

    fun close() {
        val cause = CancellationException("ConfigurationModule shut down")
        requests.close(cause)
        active.keys.forEach { it.fail(cause) }
        active.clear()
    }

    companion object {
        internal const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
