package pro.getline.vpn.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService

@Suppress("DEPRECATION")
fun Context.hasValidatedInternetConnection(): Boolean {
    val connectivity = getSystemService<ConnectivityManager>() ?: return false

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        connectivity.allNetworks.any { network ->
            val capabilities =
                connectivity.getNetworkCapabilities(network) ?: return@any false

            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    } else {
        connectivity.allNetworks.any { network ->
            val info = connectivity.getNetworkInfo(network) ?: return@any false

            info.type != ConnectivityManager.TYPE_VPN && info.isConnected
        }
    }
}
