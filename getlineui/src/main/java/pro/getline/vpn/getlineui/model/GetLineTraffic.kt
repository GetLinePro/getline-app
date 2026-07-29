package pro.getline.vpn.getlineui.model

data class GetLineTraffic(
    val uploadedBytes: Long,
    val downloadedBytes: Long,
) {
    val totalBytes: Long get() = uploadedBytes + downloadedBytes

    companion object {
        val Zero = GetLineTraffic(0L, 0L)
    }
}
