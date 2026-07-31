package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.TimeUnit

class ProfileWorker : BaseService() {
    private val service: ProfileWorker
        get() = this

    private val jobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()

        createChannels()

        foreground()

        launch {
            delay(TimeUnit.SECONDS.toMillis(10))

            while (true) {
                jobs.removeFirstOrNull()?.join() ?: break
            }

            stopSelf()
        }
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                intent.uuid?.also {
                    val job = launch {
                        run(it)
                    }

                    jobs.add(job)
                }
            }
            Intents.ACTION_PROFILE_SCHEDULE_UPDATES -> {
                val job = launch {
                    ProfileReceiver.rescheduleAll(service)

                    delay(TimeUnit.SECONDS.toMillis(30))
                }

                jobs.add(job)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun run(uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        try {
            processing(imported.name) {
                ProfileProcessor.update(this, imported.uuid, null)
            }

            completed(imported.uuid)

            ProfileReceiver.scheduleNext(this, imported)
        } catch (e: Exception) {
            failed(imported.uuid, e.message ?: "Unknown")
        }
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_service_status)).build(),
                NotificationChannelCompat.Builder(
                    STATUS_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_process_status)).build(),
            )
        )
    }

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.profile_updater))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_profile_worker, notification)
    }

    private suspend inline fun processing(name: String, block: () -> Unit) {
        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setContentTitle(getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(id, notification)
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                NotificationManagerCompat.from(applicationContext)
                    .cancel(id)
            }
        }
    }

    // No shade result: scheduled updates are expected background work.
    // In-app listeners still get PROFILE_UPDATE_* broadcasts.
    private fun completed(uuid: UUID) {
        sendProfileUpdateCompleted(uuid)
    }

    private fun failed(uuid: UUID, reason: String) {
        sendProfileUpdateFailed(uuid, reason)
    }

    companion object {
        private const val SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}