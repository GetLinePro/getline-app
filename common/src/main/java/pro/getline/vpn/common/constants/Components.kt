package pro.getline.vpn.common.constants

import android.content.ComponentName
import pro.getline.vpn.common.util.packageName

object Components {
    private const val componentsPackageName = "pro.getline.vpn"

    val MAIN_ACTIVITY = ComponentName(packageName, "$componentsPackageName.MainActivity")
    val PROPERTIES_ACTIVITY = ComponentName(packageName, "$componentsPackageName.PropertiesActivity")
}