package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.core.model.FetchStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrimaryConfigRefresherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun noValidator_sendsNoIfNoneMatch_andStoresEtagOn200() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-first")
        val cache = temporaryFolder.newFolder("cache-first")
        val downloads = mutableListOf<String?>()
        val body = "mixed-port: 7890\n"

        val refresher = PrimaryConfigRefresher(
            download = { _, _, ifNoneMatch ->
                downloads += ifNoneMatch
                downloaded(body, etag = "\"v1\"")
            },
            validate = { path, localFile, _, _, _ ->
                path.resolve("config.yaml").writeText(localFile.readText())
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals(listOf(null), downloads)
        assertEquals(body, processing.resolve("config.yaml").readText())
        assertEquals("\"v1\"", PrimaryConfigValidator.read(processing))
    }

    @Test
    fun withValidator_sendsIfNoneMatch() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-inm")
        val cache = temporaryFolder.newFolder("cache-inm")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "\"old\"")
        val downloads = mutableListOf<String?>()

        val refresher = PrimaryConfigRefresher(
            download = { _, _, ifNoneMatch ->
                downloads += ifNoneMatch
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(
                        etag = "\"old\"",
                        subscriptionUserInfo = null,
                        profileUpdateInterval = null,
                    ),
                )
            },
            validate = { _, _, _, _, _ -> },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals(listOf("\"old\""), downloads)
    }

    @Test
    fun notModified_doesNotOverwriteBody_andReusesLocal() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-304")
        val cache = temporaryFolder.newFolder("cache-304")
        val original = "rules: [LOCAL]\n"
        processing.resolve("config.yaml").writeText(original)
        PrimaryConfigValidator.write(processing, "\"same\"")
        val validatedBodies = mutableListOf<String>()

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(
                        etag = "\"same\"",
                        subscriptionUserInfo = null,
                        profileUpdateInterval = null,
                    ),
                )
            },
            validate = { path, localFile, _, _, _ ->
                validatedBodies += localFile.readText()
                // Simulate native reinstall of the same bytes.
                path.resolve("config.yaml").writeText(localFile.readText())
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals(listOf(original), validatedBodies)
        assertEquals(original, processing.resolve("config.yaml").readText())
        assertEquals("\"same\"", PrimaryConfigValidator.read(processing))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun c1_200WithoutEtag_clearsPreviousValidatorAfterValidate() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-c1")
        val cache = temporaryFolder.newFolder("cache-c1")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "etag-A")

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                downloaded("body-b", etag = null)
            },
            validate = { path, localFile, _, _, _ ->
                path.resolve("config.yaml").writeText(localFile.readText())
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals("body-b", processing.resolve("config.yaml").readText())
        assertNull(PrimaryConfigValidator.read(processing))
    }

    @Test
    fun c2_304WithNewEtag_updatesValidator_keepsBody() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-c2-new")
        val cache = temporaryFolder.newFolder("cache-c2-new")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "etag-old")

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(
                        etag = "etag-new",
                        subscriptionUserInfo = null,
                        profileUpdateInterval = null,
                    ),
                )
            },
            validate = { _, _, _, _, _ -> },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals("body-a", processing.resolve("config.yaml").readText())
        assertEquals("etag-new", PrimaryConfigValidator.read(processing))
    }

    @Test
    fun c2_304WithoutEtag_keepsOldValidator() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-c2-keep")
        val cache = temporaryFolder.newFolder("cache-c2-keep")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "etag-old")

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(
                        etag = null,
                        subscriptionUserInfo = null,
                        profileUpdateInterval = null,
                    ),
                )
            },
            validate = { _, _, _, _, _ -> },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals("etag-old", PrimaryConfigValidator.read(processing))
    }

    @Test
    fun downloaded_validateFail_leavesValidatorUnchanged() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-fail")
        val cache = temporaryFolder.newFolder("cache-fail")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "etag-A")

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                downloaded("body-b", etag = "etag-B")
            },
            validate = { path, localFile, _, _, _ ->
                // Real ValidateAndPrepareLocalConfig writes config.yaml before
                // parse/providers (fetch.go). Body in processing may already
                // differ; committed state is protected only because update()
                // does not copy processing → imported on exception.
                path.resolve("config.yaml").writeText(localFile.readText())
                throw IOException("invalid yaml")
            },
        )

        try {
            refresher.refresh(
                context = context,
                source = "https://example.com/sub",
                allowConditional = true,
                reportStatus = {},
                processingDir = processing,
                cacheDir = cache,
            )
            fail("expected validate failure")
        } catch (e: IOException) {
            assertEquals("invalid yaml", e.message)
        }

        assertEquals("body-b", processing.resolve("config.yaml").readText())
        assertEquals("etag-A", PrimaryConfigValidator.read(processing))
    }

    @Test
    fun c3_304MissingLocal_exactlyOneUnconditionalRetry() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-c3-missing")
        val cache = temporaryFolder.newFolder("cache-c3-missing")
        PrimaryConfigValidator.write(processing, "\"stale\"")
        // no config.yaml
        val downloads = mutableListOf<String?>()
        var validateCalls = 0

        val refresher = PrimaryConfigRefresher(
            download = { _, _, ifNoneMatch ->
                downloads += ifNoneMatch
                if (ifNoneMatch != null) {
                    PrimaryConfigFetchResult.NotModified(
                        PrimaryConfigResponseMetadata(null, null, null),
                    )
                } else {
                    downloaded("body-fresh", etag = "\"fresh\"")
                }
            },
            validate = { path, localFile, _, _, _ ->
                validateCalls++
                path.resolve("config.yaml").writeText(localFile.readText())
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals(listOf("\"stale\"", null), downloads)
        assertEquals(1, validateCalls)
        assertEquals("body-fresh", processing.resolve("config.yaml").readText())
        assertEquals("\"fresh\"", PrimaryConfigValidator.read(processing))
    }

    @Test
    fun c3_304ValidateFail_exactlyOneUnconditionalRetry() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-c3-validate")
        val cache = temporaryFolder.newFolder("cache-c3-validate")
        processing.resolve("config.yaml").writeText("body-broken")
        PrimaryConfigValidator.write(processing, "\"stale\"")
        val downloads = mutableListOf<String?>()
        var validateCalls = 0

        val refresher = PrimaryConfigRefresher(
            download = { _, _, ifNoneMatch ->
                downloads += ifNoneMatch
                if (ifNoneMatch != null) {
                    PrimaryConfigFetchResult.NotModified(
                        PrimaryConfigResponseMetadata(null, null, null),
                    )
                } else {
                    downloaded("body-fresh", etag = "\"fresh\"")
                }
            },
            validate = { path, localFile, _, _, _ ->
                validateCalls++
                if (validateCalls == 1) {
                    throw IOException("providers broken")
                }
                path.resolve("config.yaml").writeText(localFile.readText())
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals(listOf("\"stale\"", null), downloads)
        assertEquals(2, validateCalls)
        assertEquals("body-fresh", processing.resolve("config.yaml").readText())
        assertEquals("\"fresh\"", PrimaryConfigValidator.read(processing))
    }

    @Test
    fun c3_doesNotRetryTwice() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-c3-once")
        val cache = temporaryFolder.newFolder("cache-c3-once")
        PrimaryConfigValidator.write(processing, "\"stale\"")
        val downloads = mutableListOf<String?>()

        val refresher = PrimaryConfigRefresher(
            download = { _, _, ifNoneMatch ->
                downloads += ifNoneMatch
                // Even unconditional returns 304 without a body — should not loop.
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(null, null, null),
                )
            },
            validate = { _, _, _, _, _ -> fail("should not validate") },
        )

        try {
            refresher.refresh(
                context = context,
                source = "https://example.com/sub",
                allowConditional = true,
                reportStatus = {},
                processingDir = processing,
                cacheDir = cache,
            )
            fail("expected failure after single retry")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("304"))
        }

        assertEquals(listOf("\"stale\"", null), downloads)
    }

    @Test
    fun metadataOn304_passesPresentHeadersToValidate() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-meta")
        val cache = temporaryFolder.newFolder("cache-meta")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "etag")
        var seenUserInfo: String? = "unset"
        var seenInterval: String? = "unset"

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(
                        etag = null,
                        subscriptionUserInfo = "upload=1; download=2; total=3",
                        profileUpdateInterval = "24",
                    ),
                )
            },
            validate = { _, _, userInfo, interval, _ ->
                seenUserInfo = userInfo
                seenInterval = interval
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals("upload=1; download=2; total=3", seenUserInfo)
        assertEquals("24", seenInterval)
    }

    @Test
    fun metadataOn304_absentHeadersBecomeEmptyStringForNative() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-meta-absent")
        val cache = temporaryFolder.newFolder("cache-meta-absent")
        processing.resolve("config.yaml").writeText("body-a")
        PrimaryConfigValidator.write(processing, "etag")
        var seenUserInfo: String? = "unset"
        var seenInterval: String? = "unset"

        val refresher = PrimaryConfigRefresher(
            download = { _, _, _ ->
                PrimaryConfigFetchResult.NotModified(
                    PrimaryConfigResponseMetadata(
                        etag = null,
                        subscriptionUserInfo = null,
                        profileUpdateInterval = null,
                    ),
                )
            },
            validate = { _, _, userInfo, interval, _ ->
                seenUserInfo = userInfo
                seenInterval = interval
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = true,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        // Empty → native reportSubscriptionInfo no-ops → counters not cleared (C4).
        assertEquals("", seenUserInfo)
        assertEquals("", seenInterval)
    }

    @Test
    fun allowConditionalFalse_neverSendsIfNoneMatchEvenWithSidecar() = runBlocking {
        val processing = temporaryFolder.newFolder("proc-apply")
        val cache = temporaryFolder.newFolder("cache-apply")
        PrimaryConfigValidator.write(processing, "\"should-not-send\"")
        val downloads = mutableListOf<String?>()

        val refresher = PrimaryConfigRefresher(
            download = { _, _, ifNoneMatch ->
                downloads += ifNoneMatch
                downloaded("body", etag = "\"n\"")
            },
            validate = { path, localFile, _, _, _ ->
                path.resolve("config.yaml").writeText(localFile.readText())
            },
        )

        refresher.refresh(
            context = context,
            source = "https://example.com/sub",
            allowConditional = false,
            reportStatus = {},
            processingDir = processing,
            cacheDir = cache,
        )

        assertEquals(listOf(null), downloads)
    }

    private fun downloaded(
        body: String,
        etag: String?,
        subscriptionUserInfo: String? = null,
        profileUpdateInterval: String? = null,
    ): PrimaryConfigFetchResult.Downloaded {
        val file = temporaryFolder.newFile("dl-${System.nanoTime()}.yaml")
        file.writeText(body)
        return PrimaryConfigFetchResult.Downloaded(
            file = file,
            metadata = PrimaryConfigResponseMetadata(
                etag = etag,
                subscriptionUserInfo = subscriptionUserInfo,
                profileUpdateInterval = profileUpdateInterval,
            ),
        )
    }
}
