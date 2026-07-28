package pro.getline.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pro.getline.vpn.service.StatusProvider
import pro.getline.vpn.util.startClashService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (StatusProvider.shouldStartClashOnBoot)
                    context.startClashService()
            }
        }
    }
}