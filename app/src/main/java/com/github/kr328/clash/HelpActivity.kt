package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.design.HelpDesign
import kotlinx.coroutines.isActive
import pro.getline.vpn.AppEnvironment

class HelpActivity : BaseActivity<HelpDesign>() {
    override suspend fun main() {
        val portalUrl = AppEnvironment.portalOrigin.trimEnd('/') + "/"
        val design = HelpDesign(
            context = this,
            openLink = { startActivity(Intent(Intent.ACTION_VIEW).setData(it)) },
            accountPortalUrl = portalUrl,
        )

        setContentDesign(design)

        while (isActive) {
            events.receive()
        }
    }
}