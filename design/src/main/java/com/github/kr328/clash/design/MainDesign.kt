package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val aboutBinding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
                this.self = this@MainDesign
            }

            AlertDialog.Builder(context)
                .setView(aboutBinding.root)
                .show()
        }
    }

    fun openSources() {
        val url = context.getString(R.string.getline_sources_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canOpen = intent.resolveActivity(context.packageManager) != null
        if (canOpen) {
            runCatching { context.startActivity(intent) }
                .onFailure { copySourcesUrl(url) }
        } else {
            copySourcesUrl(url)
        }
    }

    private fun copySourcesUrl(url: String) {
        context.getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText("sources", url))
        Toast.makeText(context, R.string.sources_open_failed, Toast.LENGTH_LONG).show()
    }

    init {
        binding.self = this

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
        // Active card fills with primary cyan — use on-primary dark ink for ≥4.5:1 contrast.
        binding.colorStatusRunningContent =
            context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
        // Status card is forced to AppThemeDark; stopped surface needs light ink.
        binding.colorStatusStoppedContent =
            context.getColorCompat(R.color.getline_brand_text)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}