package pro.getline.vpn.getline.auth

data class BrowserAuthStartResponse(
    val authUrl: String,
)

data class WebAuthCallback(
    val authToken: String,
    val expiresInSeconds: Long?,
)
