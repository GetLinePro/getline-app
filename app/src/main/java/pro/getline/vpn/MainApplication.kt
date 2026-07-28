package pro.getline.vpn

import android.app.Application
import android.content.Context
import pro.getline.vpn.common.Global
import pro.getline.vpn.common.compat.currentProcessName
import pro.getline.vpn.common.log.Log
import pro.getline.vpn.remote.Remote
import pro.getline.vpn.service.util.sendServiceRecreated
import pro.getline.vpn.util.clashDir
import java.io.File
import java.io.FileOutputStream

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()

        Log.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val geoipFile = File(clashDir, "geoip.metadb")
        if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
            geoipFile.delete()
        }
        if (!geoipFile.exists()) {
            FileOutputStream(geoipFile).use {
                assets.open("geoip.metadb").copyTo(it)
            }
        }

        val geositeFile = File(clashDir, "geosite.dat")
        if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
            geositeFile.delete()
        }
        if (!geositeFile.exists()) {
            FileOutputStream(geositeFile).use {
                assets.open("geosite.dat").copyTo(it)
            }
        }

        val asnFile = File(clashDir, "ASN.mmdb")
        if (asnFile.exists() && asnFile.lastModified() < updateDate) {
            asnFile.delete()
        }
        if (!asnFile.exists()) {
            FileOutputStream(asnFile).use {
                assets.open("ASN.mmdb").copyTo(it)
            }
        }

        val bundleMRSFile = File(clashDir, "BundleMRS.7z")
        if (bundleMRSFile.exists() && bundleMRSFile.lastModified() < updateDate) {
            bundleMRSFile.delete()
        }
        if (!bundleMRSFile.exists()) {
            FileOutputStream(bundleMRSFile).use {
                assets.open("BundleMRS.7z").copyTo(it)
            }
        }
    }

    fun finalize() {
        Global.destroy()
    }
}
