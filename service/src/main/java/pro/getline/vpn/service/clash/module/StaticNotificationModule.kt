package pro.getline.vpn.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pro.getline.vpn.common.compat.getColorCompat
import pro.getline.vpn.common.compat.pendingIntentFlags
import pro.getline.vpn.common.compat.startForegroundCompat
import pro.getline.vpn.common.constants.Components
import pro.getline.vpn.common.constants.Intents
import pro.getline.vpn.service.R
import pro.getline.vpn.service.StatusProvider
import kotlinx.coroutines.channels.Channel

class StaticNotificationModule(
    service: Service,
    private val onForegroundStarted: () -> Unit = {},
) : Module<Unit>(service) {
    private val builder = NotificationCompat.Builder(service, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
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

    override suspend fun run() {
        val loaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        while (true) {
            loaded.receive()

            val profileName = StatusProvider.currentProfile ?: "Not selected"

            val notification = builder
                .setContentTitle(profileName)
                .setContentText(service.getText(R.string.running))
                .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
            onForegroundStarted()
        }
    }

    companion object {
        const val CHANNEL_ID = "clash_status_channel"

        fun createNotificationChannel(service: Service) {
            NotificationManagerCompat.from(service).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(service.getText(R.string.clash_service_status_channel)).build()
            )
        }

        fun notifyLoadingNotification(service: Service) {
            val notification =
                NotificationCompat.Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setOngoing(true)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentTitle(service.getText(R.string.loading))
                    .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }
}
