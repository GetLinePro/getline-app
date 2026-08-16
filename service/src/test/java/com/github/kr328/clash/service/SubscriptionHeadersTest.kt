package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SubscriptionHeadersTest {
    @Test
    fun usableHeader_trimsBlankToNull() {
        assertEquals("PAID", usableSubscriptionHeader("  PAID "))
        assertNull(usableSubscriptionHeader(null))
        assertNull(usableSubscriptionHeader(""))
        assertNull(usableSubscriptionHeader("   "))
    }

    @Test
    fun usableDeviceLimit_acceptsOnlyPositiveInteger() {
        assertEquals(10, usableSubscriptionDeviceLimit(" 10 "))
        assertNull(usableSubscriptionDeviceLimit(null))
        assertNull(usableSubscriptionDeviceLimit("0"))
        assertNull(usableSubscriptionDeviceLimit("-1"))
        assertNull(usableSubscriptionDeviceLimit("many"))
    }

    @Test
    fun trafficAndMarkers_areIndependent() {
        val current = sampleImported(
            tag = "OLD",
            status = "OLD",
            deviceLimit = 3,
            upload = 1,
            download = 2,
        )
        val info = FetchStatus(
            action = FetchStatus.Action.SubscriptionInfo,
            args = emptyList(),
            progress = -1,
            max = -1,
            subUpload = 10,
            subDownload = 20,
            subTotal = 30,
            subExpire = 40,
        )
        val metadata = PrimaryConfigResponseMetadata(
            etag = "\"v\"",
            subscriptionUserInfo = null,
            profileUpdateInterval = null,
            tag = "PAID",
            status = "Active",
            deviceLimit = 10,
        )

        val both = applyImportedSubscriptionFields(current, info, metadata)
        assertEquals(10L, both.upload)
        assertEquals(20L, both.download)
        assertEquals(30L, both.total)
        assertEquals(40L, both.expire)
        assertEquals("PAID", both.tag)
        assertEquals("Active", both.status)
        assertEquals(10, both.deviceLimit)

        val markersOnly = applyImportedSubscriptionFields(current, subscriptionInfo = null, metadata)
        assertEquals(1L, markersOnly.upload)
        assertEquals("PAID", markersOnly.tag)

        val trafficOnly = applyImportedSubscriptionFields(current, info, metadata = null)
        assertEquals(10L, trafficOnly.upload)
        assertEquals("OLD", trafficOnly.tag)
        assertEquals(3, trafficOnly.deviceLimit)

        val cleared = applyImportedSubscriptionFields(
            current,
            subscriptionInfo = null,
            metadata = metadata.copy(tag = "  ", status = null, deviceLimit = null),
        )
        assertNull(cleared.tag)
        assertNull(cleared.status)
        assertNull(cleared.deviceLimit)
        assertEquals(1L, cleared.upload)
    }

    @Test
    fun storedHeaders_nativePreserves_httpsMissingClears() {
        val native = resolveStoredSubscriptionHeaders(
            currentTag = "PAID",
            currentStatus = "Active",
            currentDeviceLimit = 10,
            metadata = null,
        )
        assertEquals("PAID", native.tag)
        assertEquals("Active", native.status)
        assertEquals(10, native.deviceLimit)

        val httpsMissing = resolveStoredSubscriptionHeaders(
            currentTag = "PAID",
            currentStatus = "Active",
            currentDeviceLimit = 10,
            metadata = PrimaryConfigResponseMetadata(
                etag = null,
                subscriptionUserInfo = null,
                profileUpdateInterval = null,
            ),
        )
        assertNull(httpsMissing.tag)
        assertNull(httpsMissing.status)
        assertNull(httpsMissing.deviceLimit)
    }

    private fun sampleImported(
        tag: String?,
        status: String?,
        deviceLimit: Int?,
        upload: Long,
        download: Long,
    ) = Imported(
        uuid = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        name = "p",
        type = Profile.Type.Url,
        source = "https://example.test/sub",
        interval = 0L,
        upload = upload,
        download = download,
        total = 0L,
        expire = 0L,
        createdAt = 1L,
        tag = tag,
        status = status,
        deviceLimit = deviceLimit,
    )
}
