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
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.auth.GetLineSessionStore
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

        when (resolveLaunchTarget()) {
            LaunchTarget.Onboarding -> {
                startActivity(GetLineOnboardingActivity::class.intent)
                finish()
                return
            }
            LaunchTarget.Home -> {
                startActivity(
                    GetLineHomeActivity::class.intent.putExtra(
                        GetLineHomeActivity.EXTRA_BACKEND_UNAVAILABLE,
                        launchTargetBackendUnavailable,
                    )
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

    private enum class LaunchTarget {
        Onboarding,
        Home,
        Advanced,
    }

    private var launchTargetBackendUnavailable = false

    /**
     * Route GetLine UI without hanging forever if `:background` / RemoteService is dead.
     * Failure opens GetLine home in a recoverable state instead of exposing a blank screen.
     */
    private suspend fun resolveLaunchTarget(): LaunchTarget {
        if (intent.getBooleanExtra(EXTRA_OPEN_ADVANCED, false)) {
            // Store/backend not opened on this branch — do not invent flags for the log.
            logStartupRoute(
                dest = "advanced",
                reason = "open_advanced",
                store = "na",
                session = "na",
                managed = "na",
                pendingImport = "na",
                imported = "na",
                backend = "na",
            )
            return LaunchTarget.Advanced
        }

        launchTargetBackendUnavailable = false

        val sessionSnapshot = readSessionRoutingSnapshot()
        // store=err: EncryptedSharedPreferences/keystore failed — false flags are defaults,
        // not a proven empty session (see readSessionRoutingSnapshot).
        val store = if (sessionSnapshot.storeOk) "ok" else "err"
        val session = flag01(sessionSnapshot.hasSession)
        val managed = flag01(sessionSnapshot.hasManagedProfile)
        val pendingImport = flag01(sessionSnapshot.hasPendingImport)

        // In-flight durable import (first-time or UseAccount) resumes on Onboarding.
        // Checked before managed-profile → Home so a mid-import cold start does not
        // drop the pending UseAccount orphan-cleanup payload.
        if (sessionSnapshot.hasPendingImport) {
            logStartupRoute(
                dest = "onboarding",
                reason = "pending_import",
                store = store,
                session = session,
                managed = managed,
                pendingImport = pendingImport,
                imported = "na",
                backend = "na",
            )
            return LaunchTarget.Onboarding
        }

        // Managed binding wins. Do NOT force Onboarding solely because the post-login
        // step is incomplete (session + still-link-only): permanent failures
        // (no importable subscription / offline) would trap a working VPN profile
        // forever with only Retry/Diagnostics. Mid-dialog process death may land
        // Home with a temporary session+link-only mix; VPN stays reachable.
        if (sessionSnapshot.hasManagedProfile) {
            logStartupRoute(
                dest = "home",
                reason = "managed_profile",
                store = store,
                session = session,
                managed = managed,
                pendingImport = pendingImport,
                imported = "na",
                backend = "na",
            )
            return LaunchTarget.Home
        }

        return when (val hasImported = getLineBackend.subscriptions.hasImported()) {
            GetLineBackendResult.Unavailable -> {
                launchTargetBackendUnavailable = true
                logStartupRoute(
                    dest = "home",
                    reason = "backend_unavailable",
                    store = store,
                    session = session,
                    managed = managed,
                    pendingImport = pendingImport,
                    imported = "na",
                    backend = "unavailable",
                )
                LaunchTarget.Home
            }
            is GetLineBackendResult.Success ->
                if (hasImported.value) {
                    logStartupRoute(
                        dest = "home",
                        reason = "has_import",
                        store = store,
                        session = session,
                        managed = managed,
                        pendingImport = pendingImport,
                        imported = "1",
                        backend = "ok",
                    )
                    LaunchTarget.Home
                } else {
                    logStartupRoute(
                        dest = "onboarding",
                        reason = "no_import",
                        store = store,
                        session = session,
                        managed = managed,
                        pendingImport = pendingImport,
                        imported = "0",
                        backend = "ok",
                    )
                    LaunchTarget.Onboarding
                }
        }
    }

    /**
     * Safe GL-19 breadcrumb: enum/bool tokens only (no UUID, URL, or Exception text).
     * Fields not evaluated on this branch are [na] — never call backend solely for logging.
     * [store] is ok|err|na: err means snapshot defaults, not a proven empty session.
     */
    private fun logStartupRoute(
        dest: String,
        reason: String,
        store: String,
        session: String,
        managed: String,
        pendingImport: String,
        imported: String,
        backend: String,
    ) {
        Log.i(
            "startup_route dest=$dest reason=$reason store=$store " +
                "session=$session managed=$managed pending_import=$pendingImport " +
                "imported=$imported backend=$backend",
        )
    }

    private fun flag01(value: Boolean): String = if (value) "1" else "0"

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
                )
            }.getOrDefault(SessionRoutingSnapshot(storeOk = false))
        }

    private data class SessionRoutingSnapshot(
        /** false when [GetLineSessionStore] construction/routing reads failed (e.g. keystore). */
        val storeOk: Boolean = false,
        val hasSession: Boolean = false,
        val hasManagedProfile: Boolean = false,
        val hasPendingImport: Boolean = false,
    )

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
