package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.R

/**
 * "Disconnect" on the status notification. The broadcast is the one
 * [CloseModule] listens for, the same request `stopClashService()` sends.
 */
fun NotificationCompat.Builder.addStopAction(context: Context): NotificationCompat.Builder =
    addAction(
        R.drawable.ic_notification_stop,
        context.getText(R.string.clash_notification_stop),
        PendingIntent.getBroadcast(
            context,
            R.id.nf_clash_status_stop,
            Intent(Intents.ACTION_CLASH_REQUEST_STOP).setPackage(context.packageName),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    )
