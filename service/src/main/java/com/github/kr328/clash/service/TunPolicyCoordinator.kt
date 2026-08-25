package com.github.kr328.clash.service

import com.github.kr328.clash.service.model.AccessControlPlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1

/**
 * Serializes TUN policy apply against the latest [ServiceStore] snapshot.
 *
 * Requests are conflated: a burst B/C/D becomes one apply of D. The request
 * carries no plan — [reconcile] reads the store at execution time so a
 * broadcast that arrived before the receiver was registered is still applied
 * by the initial request.
 */
internal class TunPolicyCoordinator(
    private val readDesiredPlan: () -> AccessControlPlan,
    private val apply: (AccessControlPlan) -> Unit,
) {
    sealed class Result {
        object Unchanged : Result()
        object Applied : Result()
        data class Failed(val message: String) : Result()
    }

    var appliedPlan: AccessControlPlan? = null
        private set

    private var failure: Result.Failed? = null
    private val requests = Channel<Unit>(Channel.CONFLATED)

    val onRequest: SelectClause1<Unit>
        get() = requests.onReceive

    fun requestReconcile() {
        requests.trySend(Unit)
    }

    internal fun pollRequest(): Boolean = requests.tryReceive().isSuccess

    fun reconcile(): Result {
        failure?.let { return it }

        val desired = readDesiredPlan()
        if (desired == appliedPlan) {
            return Result.Unchanged
        }

        return try {
            apply(desired)
            appliedPlan = desired
            Result.Applied
        } catch (e: Exception) {
            Result.Failed(e.message ?: "tun policy apply failed").also { failure = it }
        }
    }
}
