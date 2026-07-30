package com.github.kr328.clash

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.design.R
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.GetLineOnboardingActivity
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.auth.GetLineAuthException
import java.util.concurrent.TimeUnit

class ExternalImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val request = parseRequest() ?: run {
            Toast.makeText(this, R.string.external_import_invalid_link, Toast.LENGTH_LONG).show()
            return finish()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.external_import_confirmation_title)
            .setMessage(getString(R.string.external_import_confirmation_message, request.host))
            .setPositiveButton(R.string.import_) { _, _ -> startImport(request) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun parseRequest(): ExternalImportRequest? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val source = uri.getQueryParameter("url")?.trim() ?: return null

        try {
            GetLineControlPlaneHostPolicy.requireSubscriptionUrl(source)
        } catch (_: GetLineAuthException.Protocol) {
            return null
        }

        val host = GetLineControlPlaneHostPolicy.canonicalizeHost(
            android.net.Uri.parse(source).host,
        ) ?: return null
        val parsedInterval = uri.getQueryParameter("update-interval")?.toLongOrNull() ?: 0L
        val updateInterval = if (parsedInterval > 0) parsedInterval.coerceAtLeast(15L) else 0L

        return ExternalImportRequest(
            host = host,
            source = source,
            intervalMs = TimeUnit.MINUTES.toMillis(updateInterval),
        )
    }

    private fun startImport(request: ExternalImportRequest) {
        startActivity(
            GetLineOnboardingActivity.importIntent(
                context = this,
                type = GetLineSubscriptionType.Url,
                name = getString(R.string.new_profile),
                source = request.source,
                interval = request.intervalMs,
            ),
        )
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private data class ExternalImportRequest(
        val host: String,
        val source: String,
        val intervalMs: Long,
    )
}
