package com.github.kr328.clash.service.clash.module

import android.app.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoadingNotificationGracePeriod(
    service: Service,
    scope: CoroutineScope,
) {
    private val notification: Job = scope.launch {
        delay(GRACE_PERIOD_MS)
        StaticNotificationModule.notifyLoadingNotification(service)
    }

    fun complete() {
        notification.cancel()
    }

    companion object {
        private const val GRACE_PERIOD_MS = 2_000L
    }
}
