package pro.getline.vpn.design.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import pro.getline.vpn.common.compat.foreground
import pro.getline.vpn.design.model.AppInfo

fun PackageInfo.toAppInfo(pm: PackageManager): AppInfo {
    return AppInfo(
        packageName = packageName,
        icon = applicationInfo!!.loadIcon(pm).foreground(),
        label = applicationInfo!!.loadLabel(pm).toString(),
        installTime = firstInstallTime,
        updateDate = lastUpdateTime,
    )
}
