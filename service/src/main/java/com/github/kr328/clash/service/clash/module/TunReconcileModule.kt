package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import kotlinx.coroutines.channels.Channel

class TunReconcileModule(
    service: Service,
    private val requestReconcile: () -> Unit,
) : Module<Unit>(service) {
    override suspend fun run() {
        val broadcasts = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_TUN_POLICY_RECONCILE)
        }

        requestReconcile()

        while (true) {
            broadcasts.receive()
            requestReconcile()
        }
    }
}
