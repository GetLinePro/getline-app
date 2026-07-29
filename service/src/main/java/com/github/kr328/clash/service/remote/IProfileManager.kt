package com.github.kr328.clash.service.remote

import com.github.kr328.clash.service.model.Profile
import com.github.kr328.kaidl.BinderInterface
import java.util.*

@BinderInterface
interface IProfileManager {
    suspend fun create(type: Profile.Type, name: String, source: String = "", ageSecretKey: String? = null): UUID
    suspend fun clone(uuid: UUID): UUID
    suspend fun commit(uuid: UUID, callback: IFetchObserver? = null)
    suspend fun release(uuid: UUID)
    suspend fun delete(uuid: UUID)
    suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?)
    /**
     * Schedules [ProfileWorker] (user-visible progress/result notifications).
     * Prefer [updateSilently] for product/background sync.
     */
    suspend fun update(uuid: UUID)
    /**
     * Force re-fetch imported URL profile in-process.
     * Emits [com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_CHANGED] on success.
     * Does not start ProfileWorker and posts no result notifications / Properties deep-links.
     */
    suspend fun updateSilently(uuid: UUID)
    suspend fun queryByUUID(uuid: UUID): Profile?
    suspend fun queryAll(): List<Profile>
    suspend fun queryActive(): Profile?
    suspend fun setActive(profile: Profile)
}
