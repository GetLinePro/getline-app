package pro.getline.vpn.util

import android.content.Context
import com.github.kr328.clash.core.bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-facing app version line(s) for About.
 * Format: package versionName, then native core version (underscores → hyphens).
 */
object AppVersionDisplay {
    suspend fun query(context: Context): String =
        withContext(Dispatchers.IO) {
            val versionName =
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "unknown"
            versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
}
