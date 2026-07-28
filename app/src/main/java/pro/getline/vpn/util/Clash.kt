package pro.getline.vpn.util

import android.content.Context
import android.content.Intent
import android.net.VpnService
import pro.getline.vpn.common.compat.startForegroundServiceCompat
import pro.getline.vpn.common.constants.Intents
import pro.getline.vpn.common.util.intent
import pro.getline.vpn.design.store.UiStore
import pro.getline.vpn.service.ClashService
import pro.getline.vpn.service.TunService
import pro.getline.vpn.service.util.sendBroadcastSelf

fun Context.startClashService(): Intent? {
    val startTun = UiStore(this).enableVpn

    if (startTun) {
        val vpnRequest = VpnService.prepare(this)
        if (vpnRequest != null)
            return vpnRequest

        startForegroundServiceCompat(TunService::class.intent)
    } else {
        startForegroundServiceCompat(ClashService::class.intent)
    }

    return null
}

fun Context.stopClashService() {
    sendBroadcastSelf(Intent(Intents.ACTION_CLASH_REQUEST_STOP))
}