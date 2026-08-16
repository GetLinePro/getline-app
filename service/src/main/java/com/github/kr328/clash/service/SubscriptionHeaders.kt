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

/**
 * Traffic counters stay gated on [FetchStatus.subUpload] so a response without
 * Subscription-Userinfo does not wipe them. Tag/status follow [metadata]:
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
    if (metadata != null) {
        next = next.copy(
            tag = usableSubscriptionHeader(metadata.tag),
            status = usableSubscriptionHeader(metadata.status),
        )
    }
    return next
}
