package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.util.processingDir
import java.io.File
import java.io.IOException

/**
 * Conditional primary-config fetch + validate + validator commit (issue #102).
 *
 * Extracted from [ProfileProcessor] so C3 retry, 304 reuse, and fail-closed
 * validator behavior can be unit-tested without JNI.
 */
internal class PrimaryConfigRefresher(
    private val download: suspend (
        context: Context,
        source: String,
        ifNoneMatch: String?,
    ) -> PrimaryConfigFetchResult,
    private val validate: suspend (
        path: File,
        localFile: File,
        subscriptionUserInfo: String,
        profileUpdateInterval: String,
        reportStatus: (FetchStatus) -> Unit,
    ) -> Unit,
) {
    /**
     * @param allowConditional when true and a post-commit validator exists in
     *   [processingDir], send If-None-Match. First import/apply passes false.
     * @param allowUnconditionalRetry C3: at most one unconditional retry after 304
     *   with unusable local body or failed validate.
     */
    suspend fun refresh(
        context: Context,
        source: String,
        allowConditional: Boolean,
        reportStatus: (FetchStatus) -> Unit,
        processingDir: File = context.processingDir,
        cacheDir: File = context.cacheDir,
        allowUnconditionalRetry: Boolean = true,
    ): PrimaryConfigResponseMetadata {
        val ifNoneMatch = if (allowConditional) {
            PrimaryConfigValidator.read(processingDir)
        } else {
            null
        }

        return download(context, source, ifNoneMatch).use { result ->
            when (result) {
                is PrimaryConfigFetchResult.Downloaded -> {
                    validate(
                        processingDir,
                        result.file,
                        // Native reportSubscriptionInfo treats empty as absent (C4).
                        result.metadata.subscriptionUserInfo.orEmpty(),
                        result.metadata.profileUpdateInterval.orEmpty(),
                        reportStatus,
                    )
                    // After successful validate: invalidate old validator before any
                    // new write so body B + etag A cannot be committed.
                    val disposition = PrimaryConfigValidator.applyAfterSuccess(
                        profileDir = processingDir,
                        notModified = false,
                        responseEtag = result.metadata.etag,
                    )
                    Log.i("primary_config commit code=200 etag_commit=${disposition.logLabel()}")
                    result.metadata
                }

                is PrimaryConfigFetchResult.NotModified -> {
                    val localConfig = processingDir.resolve("config.yaml")
                    if (!localConfig.isFile || localConfig.length() == 0L) {
                        if (allowUnconditionalRetry && ifNoneMatch != null) {
                            Log.w(
                                "primary_config code=304 local unusable; " +
                                    "unconditional retry in same op",
                            )
                            return refresh(
                                context = context,
                                source = source,
                                allowConditional = false,
                                reportStatus = reportStatus,
                                processingDir = processingDir,
                                cacheDir = cacheDir,
                                allowUnconditionalRetry = false,
                            )
                        }
                        throw IOException(
                            "HTTP 304 but local primary config is missing or empty",
                        )
                    }

                    // Avoid validate writing a file onto itself (O_TRUNC would wipe
                    // the body before the copy).
                    val reuseCopy = File.createTempFile(
                        "primary-reuse-",
                        ".yaml",
                        cacheDir,
                    )
                    try {
                        localConfig.copyTo(reuseCopy, overwrite = true)
                        try {
                            validate(
                                processingDir,
                                reuseCopy,
                                result.metadata.subscriptionUserInfo.orEmpty(),
                                result.metadata.profileUpdateInterval.orEmpty(),
                                reportStatus,
                            )
                        } catch (e: Exception) {
                            if (allowUnconditionalRetry && ifNoneMatch != null) {
                                Log.w(
                                    "primary_config code=304 validate failed; " +
                                        "unconditional retry in same op: $e",
                                )
                                return refresh(
                                    context = context,
                                    source = source,
                                    allowConditional = false,
                                    reportStatus = reportStatus,
                                    processingDir = processingDir,
                                    cacheDir = cacheDir,
                                    allowUnconditionalRetry = false,
                                )
                            }
                            throw e
                        }
                        val disposition = PrimaryConfigValidator.applyAfterSuccess(
                            profileDir = processingDir,
                            notModified = true,
                            responseEtag = result.metadata.etag,
                        )
                        Log.i(
                            "primary_config commit code=304 etag_commit=${disposition.logLabel()}",
                        )
                        result.metadata
                    } finally {
                        if (!reuseCopy.delete()) {
                            Log.w("Unable to delete primary config reuse temp file")
                        }
                    }
                }
            }
        }
    }
}
