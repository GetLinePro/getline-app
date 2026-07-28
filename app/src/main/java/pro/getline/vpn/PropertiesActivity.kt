package pro.getline.vpn

import android.content.Intent
import pro.getline.vpn.common.util.intent
import pro.getline.vpn.common.util.setUUID
import pro.getline.vpn.common.util.uuid
import pro.getline.vpn.design.PropertiesDesign
import pro.getline.vpn.design.ui.ToastDuration
import pro.getline.vpn.design.util.showExceptionToast
import pro.getline.vpn.service.model.Profile
import pro.getline.vpn.util.hasValidatedInternetConnection
import pro.getline.vpn.util.withProfile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import pro.getline.vpn.design.R

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    private var canceled: Boolean = false
    private lateinit var original: Profile

    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val design = PropertiesDesign(this)

        original = withProfile { queryByUUID(uuid) } ?: return finish()

        design.profile = original
        // GetLine product import: hide CMFA provider names in the progress dialog.
        // Advanced/manual edit keeps the original technical copy.
        design.useProductFetchVocabulary =
            intent.getBooleanExtra(EXTRA_GET_LINE_IMPORT, false)

        setContentDesign(design)

        defer {
            canceled = true

            withProfile { release(uuid) }
        }

        val autoCommit = intent.getBooleanExtra(EXTRA_GET_LINE_IMPORT, false) &&
            original.type != Profile.Type.File &&
            original.source.isNotBlank()

        if (autoCommit) {
            design.verifyAndCommit()
            return
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            if (!canceled && profile != original) {
                                withProfile {
                                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                                }
                            }
                        }
                        Event.ServiceRecreated -> {
                            finish()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        PropertiesDesign.Request.BrowseFiles -> {
                            startActivity(FilesActivity::class.intent.setUUID(uuid))
                        }
                        PropertiesDesign.Request.Commit -> {
                            design.verifyAndCommit()
                        }
                    }
                }
            }
        }
    }

    override fun handleBackPressed() {
        design?.apply {
            launch {
                if (!progressing) {
                    if (original == profile || requestExitWithoutSaving())
                        finish()
                }
            }
        } ?: return super.handleBackPressed()
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            else -> {
                try {
                    withProcessing { updateStatus ->
                        withProfile {
                            patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)

                            coroutineScope {
                                commit(profile.uuid) {
                                    launch {
                                        updateStatus(it)
                                    }
                                }
                            }
                        }
                    }

                    val resultData = if (intent.getBooleanExtra(EXTRA_GET_LINE_IMPORT, false)) {
                        Intent()
                            .putExtra(EXTRA_GET_LINE_COMMITTED_SOURCE, profile.source)
                            .putExtra(EXTRA_GET_LINE_COMMITTED_NAME, profile.name)
                    } else {
                        null
                    }
                    setResult(RESULT_OK, resultData)

                    finish()
                } catch (e: Exception) {
                    if (intent.getBooleanExtra(EXTRA_GET_LINE_IMPORT, false)) {
                        setResult(
                            RESULT_GET_LINE_IMPORT_FAILED,
                            Intent().putExtra(
                                EXTRA_GET_LINE_IMPORT_OFFLINE,
                                !hasValidatedInternetConnection(),
                            )
                        )
                        finish()
                    } else {
                        showExceptionToast(e)
                    }
                }
            }
        }
    }

    companion object {
        internal const val EXTRA_GET_LINE_IMPORT =
            "pro.getline.vpn.extra.GET_LINE_IMPORT"
        internal const val EXTRA_GET_LINE_IMPORT_OFFLINE =
            "pro.getline.vpn.extra.GET_LINE_IMPORT_OFFLINE"
        /** Committed subscription URL after GetLine import (for managed binding). */
        internal const val EXTRA_GET_LINE_COMMITTED_SOURCE =
            "pro.getline.vpn.extra.GET_LINE_COMMITTED_SOURCE"
        internal const val EXTRA_GET_LINE_COMMITTED_NAME =
            "pro.getline.vpn.extra.GET_LINE_COMMITTED_NAME"
        internal const val RESULT_GET_LINE_IMPORT_FAILED = RESULT_FIRST_USER
    }
}
