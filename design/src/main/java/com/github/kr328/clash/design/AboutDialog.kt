package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.util.layoutInflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared "About" dialog used from Help (product + advanced).
 * Not tied to [MainDesign] so binding is not hard-coded to one screen.
 */
object AboutDialog {
    suspend fun show(context: Context, versionName: String) {
        withContext(Dispatchers.Main) {
            val aboutBinding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }
            aboutBinding.sourcesLink.setOnClickListener {
                openSources(context)
            }

            AlertDialog.Builder(context)
                .setView(aboutBinding.root)
                .show()
        }
    }

    fun openSources(context: Context) {
        val url = context.getString(R.string.getline_sources_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canOpen = intent.resolveActivity(context.packageManager) != null
        if (canOpen) {
            runCatching { context.startActivity(intent) }
                .onFailure { copySourcesUrl(context, url) }
        } else {
            copySourcesUrl(context, url)
        }
    }

    private fun copySourcesUrl(context: Context, url: String) {
        context.getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText("sources", url))
        Toast.makeText(context, R.string.sources_open_failed, Toast.LENGTH_LONG).show()
    }
}
