package pro.getline.vpn.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import pro.getline.vpn.common.compat.getColorCompat
import pro.getline.vpn.common.compat.pendingIntentFlags
import pro.getline.vpn.common.compat.startForegroundCompat
import pro.getline.vpn.common.constants.Components
import pro.getline.vpn.common.constants.Intents
import pro.getline.vpn.common.util.ticker
import pro.getline.vpn.core.Clash
import pro.getline.vpn.core.util.trafficDownload
import pro.getline.vpn.core.util.trafficUpload
import pro.getline.vpn.service.R
import pro.getline.vpn.service.StatusProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class DynamicNotificationModule(
    service: Service,
    private val onForegroundStarted: () -> Unit = {},
) : Module<Unit>(service) {
    private val builder = NotificationCompat.Builder(service, StaticNotificationModule.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentTitle("Not Selected")
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                service,
                R.id.nf_clash_status,
                Intent().setComponent(Components.MAIN_ACTIVITY)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )

    private val notificationManager = NotificationManagerCompat.from(service)
    private var foregroundStarted = false

    private fun update() {
        val now = Clash.queryTrafficNow()
        val total = Clash.queryTrafficTotal()

        val uploading = now.trafficUpload()
        val downloading = now.trafficDownload()
        val uploaded = total.trafficUpload()
        val downloaded = total.trafficDownload()

        val notification = builder
            .setContentTitle(StatusProvider.currentProfile ?: "Not selected")
            .setContentText(
                service.getString(
                    R.string.clash_notification_content,
                    "$uploading/s", "$downloading/s"
                )
            )
            .setSubText(
                service.getString(
                    R.string.clash_notification_content,
                    uploaded, downloaded
                )
            )
            .build()

        if (foregroundStarted) {
            notificationManager.notify(R.id.nf_clash_status, notification)
        } else {
            service.startForegroundCompat(R.id.nf_clash_status, notification)
            foregroundStarted = true
            onForegroundStarted()
        }
    }

    override suspend fun run() = coroutineScope {
        var shouldUpdate = service.getSystemService<PowerManager>()?.isInteractive ?: true

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (true) {
            select<Unit> {
                screenToggle.onReceive {
                    when (it.action) {
                        Intent.ACTION_SCREEN_ON ->
                            shouldUpdate = true
                        Intent.ACTION_SCREEN_OFF ->
                            shouldUpdate = false
                    }
                }
                profileLoaded.onReceive {
                    builder.setContentTitle(StatusProvider.currentProfile ?: "Not selected")
                    update()
                }
                if (shouldUpdate) {
                    ticker.onReceive {
                        if (StatusProvider.currentProfile != null) {
                            update()
                        }
                    }
                }
            }
        }
    }
}
