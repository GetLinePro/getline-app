package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import pro.getline.vpn.getline.GetLineBackendProvider
import pro.getline.vpn.getline.LaunchRoute
import pro.getline.vpn.getline.LaunchTarget
import pro.getline.vpn.getline.SessionRoutingSnapshot
import pro.getline.vpn.getline.StartupRoutingPolicy
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.isAdvancedLaunch
import pro.getline.vpn.GetLineHomeActivity
import pro.getline.vpn.GetLineOnboardingActivity
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    private val getLineBackend by lazy { GetLineBackendProvider.create(this) }

    override suspend fun main() {
        // Avoid a blank light window while waiting for RemoteService / routing.
        withContext(Dispatchers.Main) {
            window.decorView.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, DesignR.color.getline_brand_background)
            )
        }

        val route = resolveLaunchTarget()
        when (route.target) {
            LaunchTarget.Onboarding -> {
                startActivity(
                    GetLineOnboardingActivity::class.intent.putExtra(
                        GetLineOnboardingActivity.EXTRA_SESSION_STORAGE_RECOVERED,
                        route.snapshot?.sessionStorageRecovered == true,
                    ).putExtra(
                        GetLineOnboardingActivity.EXTRA_BACKEND_UNAVAILABLE,
                        route.backendUnavailable,
                    ),
                )
                finish()
                return
            }
            LaunchTarget.Home -> {
                startActivity(
                    GetLineHomeActivity::class.intent.putExtra(
                        GetLineHomeActivity.EXTRA_BACKEND_UNAVAILABLE,
                        route.backendUnavailable,
                    ).putExtra(
                        GetLineHomeActivity.EXTRA_SESSION_STORAGE_RECOVERED,
                        route.snapshot?.sessionStorageRecovered == true,
                    ),
                )
                finish()
                return
            }
            LaunchTarget.Advanced -> Unit
        }

        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    /**
     * Route GetLine UI without hanging forever if `:background` / RemoteService is dead.
     * Failure opens GetLine home in a recoverable state instead of exposing a blank screen.
     *
     * The decision itself lives in [StartupRoutingPolicy]; this only supplies the
     * two inputs and prints the breadcrumb.
     */
    private suspend fun resolveLaunchTarget(): LaunchRoute {
        val route = StartupRoutingPolicy.decide(
            // GL-22: Advanced hatch is debug-only; release ignores the extra.
            openAdvanced = isAdvancedLaunch(
                openAdvancedExtra = intent.getBooleanExtra(EXTRA_OPEN_ADVANCED, false),
                isDebugBuild = BuildConfig.DEBUG,
            ),
            readSnapshot = ::readSessionRoutingSnapshot,
            hasImported = { getLineBackend.subscriptions.hasImported() },
        )
        logStartupRoute(route)
        return route
    }

    /**
     * Safe GL-19 breadcrumb: enum/bool tokens only (no UUID, URL, or Exception text).
     * Fields not evaluated on this branch are `na` — never call backend solely for
     * logging, which is why an unread snapshot prints `na` instead of zeros.
     * `store=err` means snapshot defaults, not a proven empty session.
     * `prefs` reports the encrypted backend; `prefs_other` reports whether the
     * removed plaintext legacy file somehow still exists.
     */
    private fun logStartupRoute(route: LaunchRoute) {
        val snapshot = route.snapshot
        val store = when {
            snapshot == null -> "na"
            snapshot.storeOk -> "ok"
            else -> "err"
        }
        Log.i(
            "startup_route dest=${route.target.name.lowercase()} reason=${route.reason} " +
                "store=$store prefs=${snapshot?.prefsBackend ?: "na"} " +
                "prefs_other=${snapshot?.prefsOther ?: "na"} " +
                "prefs_raw=${snapshot?.prefsRaw ?: "na"} " +
                "prefs_age_h=${snapshot?.prefsAgeHours ?: "na"} " +
                "prefs_reset=${flag01(snapshot?.sessionStorageRecovered)} " +
                "session=${flag01(snapshot?.hasSession)} " +
                "managed=${flag01(snapshot?.hasManagedProfile)} " +
                "pending_import=${flag01(snapshot?.hasPendingImport)} " +
                "imported=${route.imported} backend=${route.backend}",
        )
    }

    private fun flag01(value: Boolean?): String = when (value) {
        null -> "na"
        true -> "1"
        false -> "0"
    }

    /** One store init per launch route (MasterKey is not free). */
    private suspend fun readSessionRoutingSnapshot(): SessionRoutingSnapshot =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = GetLineSessionStore(this@MainActivity)
                // Routing fields first. hasSession is log-only on this path — isolate so a
                // failed refresh_token decrypt cannot collapse pending_import / managed.
                val hasManagedProfile = !store.managedProfileUuid.isNullOrBlank()
                val hasPendingImport = store.hasPendingImport()
                val hasSession = runCatching { store.hasRefreshToken() }.getOrDefault(false)
                SessionRoutingSnapshot(
                    storeOk = true,
                    hasSession = hasSession,
                    hasManagedProfile = hasManagedProfile,
                    hasPendingImport = hasPendingImport,
                    sessionStorageRecovered = store.recoveredFromStorageFailure,
                    prefsBackend = store.backendName,
                    prefsOther = runCatching { flag01(store.otherPrefsFileExists()) }
                        .getOrDefault("na"),
                    prefsRaw = runCatching { store.rawEntryCount().toString() }
                        .getOrDefault("na"),
                    prefsAgeHours = runCatching { store.backingFileAgeHours().toString() }
                        .getOrDefault("na"),
                )
            }.getOrDefault(SessionRoutingSnapshot(storeOk = false))
        }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }

    companion object {
        internal const val EXTRA_OPEN_ADVANCED =
            "pro.getline.vpn.extra.OPEN_ADVANCED"
    }
}
