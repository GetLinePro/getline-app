@file:UseSerializers(UUIDSerializer::class)

package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import com.github.kr328.clash.service.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

/**
 * Profile state read while holding the same service-side lock as profile
 * directory replacement.
 *
 * [importedUuids] is the complete DAO inventory. [checked] contains storage
 * health only for the active and requested managed candidates, keeping this
 * snapshot narrow instead of mirroring the whole profile model.
 */
@Serializable
data class ProfileStateSnapshot(
    val activeUuid: UUID?,
    val importedUuids: List<UUID>,
    val checked: List<ProfileStorageSnapshot>,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProfileStateSnapshot> {
        override fun createFromParcel(parcel: Parcel): ProfileStateSnapshot {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProfileStateSnapshot?> = arrayOfNulls(size)
    }
}

@Serializable
data class ProfileStorageSnapshot(
    val uuid: UUID,
    val health: ProfileStorageHealth,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProfileStorageSnapshot> {
        override fun createFromParcel(parcel: Parcel): ProfileStorageSnapshot {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProfileStorageSnapshot?> = arrayOfNulls(size)
    }
}

@Serializable
enum class ProfileStorageHealth {
    Intact,
    MissingDirectory,
    MissingConfig,
    EmptyConfig,
}
