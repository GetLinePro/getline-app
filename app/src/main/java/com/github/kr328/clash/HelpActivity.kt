package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.design.AboutDialog
import com.github.kr328.clash.design.HelpDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.diagnostics.DiagnosticReportShare
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.util.AppVersionDisplay

class HelpActivity : BaseActivity<HelpDesign>() {
    override suspend fun main() {
        val portalUrl = AppEnvironment.portalOrigin.trimEnd('/') + "/"
        val design = HelpDesign(
            context = this,
            openLink = { startActivity(Intent(Intent.ACTION_VIEW).setData(it)) },
            openAbout = {
                launch {
                    AboutDialog.show(this@HelpActivity, AppVersionDisplay.query(this@HelpActivity))
                }
            },
            accountPortalUrl = portalUrl,
            openSendDiagnostics = {
                launch {
                    // Keystore + EncryptedSharedPreferences only when the user asks —
                    // not on every Help open (main thread).
                    val hasSession = withContext(Dispatchers.IO) {
                        GetLineSessionStore(this@HelpActivity).hasRefreshToken()
                    }
                    DiagnosticReportShare.present(
                        activity = this@HelpActivity,
                        hasSession = hasSession,
                    )
                }
            },
        )

        setContentDesign(design)

        while (isActive) {
            events.receive()
        }
    }
}
