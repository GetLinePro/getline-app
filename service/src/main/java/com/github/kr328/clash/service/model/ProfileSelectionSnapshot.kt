@file:UseSerializers(UUIDSerializer::class)

package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import com.github.kr328.clash.service.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@Serializable
data class ProfileSelectorChoice(
    val group: String,
    val selected: String,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProfileSelectorChoice> {
        override fun createFromParcel(parcel: Parcel): ProfileSelectorChoice {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProfileSelectorChoice?> = arrayOfNulls(size)
    }
}

/**
 * Active imported profile plus every persisted selector choice.
 * One Binder round-trip so catalog read and selection write share a UUID.
 */
@Serializable
data class ProfileSelectionSnapshot(
    val uuid: UUID,
    val choices: List<ProfileSelectorChoice>,
) : Parcelable {
    fun asMap(): Map<String, String> =
        choices.associate { it.group to it.selected }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProfileSelectionSnapshot> {
        override fun createFromParcel(parcel: Parcel): ProfileSelectionSnapshot {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProfileSelectionSnapshot?> = arrayOfNulls(size)
    }
}
