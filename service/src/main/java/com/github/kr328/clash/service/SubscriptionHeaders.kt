package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Imported

/**
 * Trimmed non-empty subscription attribute. Blank / missing → null.
 * Charset and length are enforced at display time, not here.
 */
internal fun usableSubscriptionHeader(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    return trimmed.takeIf { it.isNotEmpty() }
}

/** Only a positive, in-range integer is a displayable device allowance. */
internal fun usableSubscriptionDeviceLimit(raw: String?): Int? =
    raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }

internal data class StoredSubscriptionHeaders(
    val tag: String?,
    val status: String?,
    val deviceLimit: Int?,
)

/** Native fetches have no platform metadata and therefore preserve all fields. */
internal fun resolveStoredSubscriptionHeaders(
    currentTag: String?,
    currentStatus: String?,
    currentDeviceLimit: Int?,
    metadata: PrimaryConfigResponseMetadata?,
): StoredSubscriptionHeaders {
    if (metadata == null) {
        return StoredSubscriptionHeaders(currentTag, currentStatus, currentDeviceLimit)
    }
    return StoredSubscriptionHeaders(
        tag = usableSubscriptionHeader(metadata.tag),
        status = usableSubscriptionHeader(metadata.status),
        deviceLimit = metadata.deviceLimit,
    )
}

/**
 * Traffic counters stay gated on [FetchStatus.subUpload] so a response without
 * Subscription-Userinfo does not wipe them. Display headers follow [metadata]:
 * present header → write; successful https response without the header → clear;
 * no platform metadata (native http:// path) → leave stored values alone.
 */
internal fun applyImportedSubscriptionFields(
    imported: Imported,
    subscriptionInfo: FetchStatus?,
    metadata: PrimaryConfigResponseMetadata?,
): Imported {
    var next = imported
    val upload = subscriptionInfo?.subUpload
    if (upload != null) {
        next = next.copy(
            upload = upload,
            download = subscriptionInfo.subDownload ?: 0,
            total = subscriptionInfo.subTotal ?: 0,
            expire = subscriptionInfo.subExpire ?: 0,
        )
    }
    val headers = resolveStoredSubscriptionHeaders(
        next.tag,
        next.status,
        next.deviceLimit,
        metadata,
    )
    next = next.copy(
        tag = headers.tag,
        status = headers.status,
        deviceLimit = headers.deviceLimit,
    )
    return next
}
