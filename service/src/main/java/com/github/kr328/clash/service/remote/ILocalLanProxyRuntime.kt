package com.github.kr328.clash.service.remote

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import com.github.kr328.kaidl.BinderInterface
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The private runtime command/result vocabulary for the local LAN proxy.
 * Deliberately narrow: no revision, reload, Session override or Android
 * `Network` ever crosses this boundary (see plan Module boundary) — only
 * user-facing configuration in, and a product-level outcome out. This is an
 * implementation seam for the facade (added in a later step); it is not a
 * second product API and nothing else should call it.
 */
@Serializable
data class LocalLanProxyRuntimeConfig(
    @SerialName("port")
    val port: Int,

    @SerialName("username")
    val username: String,

    @SerialName("password")
    val password: String,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<LocalLanProxyRuntimeConfig> {
        override fun createFromParcel(parcel: Parcel): LocalLanProxyRuntimeConfig {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<LocalLanProxyRuntimeConfig?> {
            return arrayOfNulls(size)
        }
    }
}

@Serializable
data class LocalLanProxyRuntimeResult(
    @SerialName("status")
    val status: Status,

    @SerialName("message")
    val message: String? = null,

    @SerialName("endpoint-address")
    val endpointAddress: String? = null,

    @SerialName("endpoint-port")
    val endpointPort: Int? = null,
) : Parcelable {
    @Serializable
    enum class Status {
        @SerialName("enabled")
        Enabled,

        @SerialName("disabled")
        Disabled,

        @SerialName("vpn-unavailable")
        VpnUnavailable,

        @SerialName("no-eligible-endpoint")
        NoEligibleEndpoint,

        @SerialName("port-occupied")
        PortOccupied,

        @SerialName("apply-failed")
        ApplyFailed,

        @SerialName("safety-stop")
        SafetyStop,
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<LocalLanProxyRuntimeResult> {
        override fun createFromParcel(parcel: Parcel): LocalLanProxyRuntimeResult {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<LocalLanProxyRuntimeResult?> {
            return arrayOfNulls(size)
        }
    }
}

@BinderInterface
interface ILocalLanProxyRuntime {
    suspend fun enable(config: LocalLanProxyRuntimeConfig): LocalLanProxyRuntimeResult
    suspend fun disable(): LocalLanProxyRuntimeResult
}
