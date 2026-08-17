package com.github.kr328.clash.common.compat

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build

fun Context.startForegroundServiceCompat(intent: Intent) {
    if (Build.VERSION.SDK_INT >= 26) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}

fun Service.startForegroundCompat(
    id: Int,
    notification: Notification,
    foregroundServiceType: Int,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(id, notification, foregroundServiceType)
    } else {
        startForeground(id, notification)
    }
}
