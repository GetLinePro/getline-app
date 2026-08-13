package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AccessControlActivity : BaseActivity<AccessControlDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            service.accessControlPackages.toMutableSet()
        }
        // The mode row writes through to the store as it is tapped, so the value
        // read here is the only record of what the running tunnel was built with.
        val initialMode = withContext(Dispatchers.IO) {
            service.accessControlMode
        }

        defer {
            withContext(Dispatchers.IO) {
                val changed = selected != service.accessControlPackages ||
                    initialMode != service.accessControlMode
                service.accessControlPackages = selected
                if (clashRunning && changed) {
                    stopClashService()
                    while (clashRunning) {
                        delay(200)
                    }
                    startClashService()
                }
            }
        }

        val design = AccessControlDesign(this, uiStore, service, initialMode, selected)

        setContentDesign(design)

        design.requests.send(AccessControlDesign.Request.ReloadApps)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        AccessControlDesign.Request.ReloadApps -> {
                            design.patchApps(loadApps(selected))
                        }

                        AccessControlDesign.Request.SelectAll -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName)
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectNone -> {
                            selected.clear()

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectInvert -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName).toSet() - selected
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            val data = clipboard?.primaryClip

                            if (data != null && data.itemCount > 0) {
                                val packages = data.getItemAt(0).text.split("\n").toSet()
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                selected.clear()
                                selected.addAll(all)
                            }

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Export -> {
                            val clipboard = getSystemService<ClipboardManager>()

                            val data = ClipData.newPlainText(
                                "packages",
                                selected.joinToString("\n")
                            )

                            clipboard?.setPrimaryClip(data)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort

            val base = compareByDescending<AppInfo> { it.packageName in selected }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            // What the user recognises is a launcher icon, not a partition. FLAG_SYSTEM
            // deliberately does not filter here: Chrome, Gmail and the dialer are system
            // packages on most builds and are ordinary apps to the person routing them.
            val launchable = launchablePackages(pm)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true
                }
                .filter {
                    it.packageName in launchable
                }
                .map {
                    it.toAppInfo(pm)
                }
                .sortedWith(comparator)
                .toList()
        }

    /**
     * Packages with a launcher entry, in either phone or TV form.
     *
     * This is a presentation filter only. A stored selection is never narrowed by it —
     * a package that loses its launcher activity, or is temporarily uninstalled, keeps
     * its routing decision in [ServiceStore.accessControlPackages].
     */
    private fun launchablePackages(pm: PackageManager): Set<String> {
        val categories = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)

        return categories.flatMapTo(mutableSetOf()) { category ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it.activityInfo.packageName }
        }
    }
}