package pro.getline.vpn.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AgeKeyPair(
    val secretKey: String,
    val publicKey: String
)
