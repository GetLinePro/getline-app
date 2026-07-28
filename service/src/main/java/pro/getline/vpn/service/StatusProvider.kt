package pro.getline.vpn.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import pro.getline.vpn.common.Global

class StatusProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_CURRENT_PROFILE -> {
                return if (serviceRunning)
                    Bundle().apply {
                        putString("name", currentProfile)
                    }
                else
                    null
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw IllegalArgumentException("Stub!")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        throw IllegalArgumentException("Stub!")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun getType(uri: Uri): String? {
        throw IllegalArgumentException("Stub!")
    }

    override fun onCreate(): Boolean {
        return true
    }

    companion object {
        const val METHOD_CURRENT_PROFILE = "currentProfile"

        private const val CLASH_SERVICE_RUNNING_FILE = "service_running.lock"

        var serviceRunning: Boolean = false
            set(value) {
                field = value

                // Session clock lives and dies with the service that owns the
                // tunnel, so it never has to be validated against a reboot.
                serviceStartedAtElapsed = if (value) SystemClock.elapsedRealtime() else null

                shouldStartClashOnBoot = value
            }

        /**
         * elapsedRealtime when the tunnel service came up, or null when it is
         * down. Monotonic, so changing the system clock cannot move it.
         *
         * Held in memory in the service process on purpose: if that process is
         * gone the tunnel is gone too, and there is nothing to resume.
         */
        var serviceStartedAtElapsed: Long? = null
            private set
        var shouldStartClashOnBoot: Boolean
            get() = Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).exists()
            set(value) {
                Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).apply {
                    if (value)
                        createNewFile()
                    else
                        delete()
                }
            }
        var currentProfile: String? = null
    }
}