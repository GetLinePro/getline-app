package pro.getline.vpn

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import pro.getline.vpn.common.constants.Intents
import pro.getline.vpn.common.util.intent
import pro.getline.vpn.common.util.setUUID
import pro.getline.vpn.design.NewProfileDesign
import pro.getline.vpn.design.R
import pro.getline.vpn.design.model.ProfileProvider
import pro.getline.vpn.design.util.showExceptionToast
import pro.getline.vpn.service.model.Profile
import pro.getline.vpn.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*

class NewProfileActivity : BaseActivity<NewProfileDesign>() {
    private val self: NewProfileActivity
        get() = this

    private val scanLauncher = registerForActivityResult(ScanQrCode(), ::scanResultHandler)

    override suspend fun main() {
        val design = NewProfileDesign(this)

        design.patchProviders(queryProfileProviders())

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        is NewProfileDesign.Request.Create -> {
                            withProfile {
                                val name = getString(R.string.new_profile)

                                val uuid: UUID? = when (val p = it.provider) {
                                    is ProfileProvider.File ->
                                        create(Profile.Type.File, name)

                                    is ProfileProvider.Url ->
                                        create(Profile.Type.Url, name)

                                    is ProfileProvider.QR -> {
                                        null
                                    }

                                    is ProfileProvider.External -> {
                                        val data = p.get()

                                        if (data != null) {
                                            val (uri, initialName) = data

                                            create(
                                                Profile.Type.External,
                                                initialName ?: name,
                                                uri.toString()
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                }

                                if (uuid != null)
                                    launchProperties(uuid)
                            }
                        }

                        is NewProfileDesign.Request.OpenDetail -> {
                            launchAppDetailed(it.provider)
                        }

                        is NewProfileDesign.Request.LaunchScanner -> {
                            scanLauncher.launch(Unit)
                        }
                    }
                }
            }
        }
    }

    private fun launchAppDetailed(provider: ProfileProvider.External) {
        val data = Uri.fromParts(
            "package",
            provider.intent.component?.packageName ?: return,
            null
        )

        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(data))
    }

    private suspend fun launchProperties(uuid: UUID) {
        val r = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            PropertiesActivity::class.intent.setUUID(uuid)
        )

        if (r.resultCode == Activity.RESULT_OK)
            finish()
    }

    private suspend fun ProfileProvider.External.get(): Pair<Uri, String?>? {
        val result = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            intent
        )

        if (result.resultCode != RESULT_OK)
            return null

        val uri = result.data?.data
        val name = result.data?.getStringExtra(Intents.EXTRA_NAME)

        if (uri != null) {
            return uri to name
        }

        return null
    }

    private suspend fun queryProfileProviders(): List<ProfileProvider> {
        return withContext(Dispatchers.IO) {
            val providers = packageManager.queryIntentActivities(
                Intent(Intents.ACTION_PROVIDE_URL),
                0
            ).map {
                val activity = it.activityInfo

                val name = activity.applicationInfo.loadLabel(packageManager)
                val summary = activity.loadLabel(packageManager)
                val icon = activity.loadIcon(packageManager)
                val intent = Intent(Intents.ACTION_PROVIDE_URL)
                    .setComponent(
                        ComponentName(
                            activity.packageName,
                            activity.name
                        )
                    )

                ProfileProvider.External(name.toString(), summary.toString(), icon, intent)
            }

            listOf(
                ProfileProvider.File(self),
                ProfileProvider.Url(self),
                ProfileProvider.QR(self)
            ) + providers
        }
    }

    private fun scanResultHandler(result: QrScanResult) {
        lifecycleScope.launch {
            when (result) {
                is QrScanResult.Success -> createProfileByQrCode(result.content)
                QrScanResult.UserCanceled -> {}
                QrScanResult.MissingPermission ->
                    design?.showExceptionToast(getString(R.string.import_from_qr_no_permission))
                QrScanResult.Error ->
                    design?.showExceptionToast(getString(R.string.import_from_qr_exception))
            }
        }
    }

    private suspend fun createProfileByQrCode(url: String) {
        withProfile {
            launchProperties(
                create(
                    type = Profile.Type.Url,
                    name = getString(R.string.new_profile),
                    url,
                )
            )
        }
    }

}
