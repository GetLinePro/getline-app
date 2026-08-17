package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProfileSelectionSnapshot
import com.github.kr328.clash.service.model.ProfileSelectorChoice
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.*

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)

    init {
        launch {
            Database.database //.init
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String, ageSecretKey: String?): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = 0,
            upload = 0,
            total = 0,
            download = 0,
            expire = 0,
            ageSecretKey = ageSecretKey,
        )

        PendingDao().insert(pending)

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun clone(uuid: UUID): UUID {
        val newUUID = generateProfileUUID()

        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw FileNotFoundException("profile $uuid not found")

        val pending = Pending(
            uuid = newUUID,
            name = imported.name,
            type = Profile.Type.File,
            source = imported.source,
            interval = imported.interval,
            upload = imported.upload,
            total = imported.total,
            download = imported.download,
            expire = imported.expire,
            ageSecretKey = imported.ageSecretKey
        )

        cloneImportedFiles(uuid, newUUID)

        PendingDao().insert(pending)

        return newUUID
    }

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) {
        val pending = PendingDao().queryByUUID(uuid)

        if (pending == null) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            cloneImportedFiles(uuid)

            PendingDao().insert(
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    upload = 0,
                    total = 0,
                    download = 0,
                    expire = 0,
                    ageSecretKey = ageSecretKey,
                )
            )
        } else {
            val newPending = pending.copy(
                name = name,
                source = source,
                interval = interval,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                ageSecretKey = ageSecretKey,
            )

            PendingDao().update(newPending)
        }
    }

    override suspend fun update(uuid: UUID) {
        updateSilently(uuid)
    }

    override suspend fun updateSilently(uuid: UUID) {
        // In-process fetch. Periodic schedule is owned by app WorkManager
        // for the GetLine managed profile only.
        ProfileProcessor.update(context, uuid, null)
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)
    }

    override suspend fun release(uuid: UUID) {
        ProfileProcessor.release(context, uuid)
    }

    override suspend fun delete(uuid: UUID) {
        ProfileProcessor.delete(context, uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? {
        return resolveProfile(uuid)
    }

    override suspend fun queryAll(): List<Profile> {
        val uuids = withContext(Dispatchers.IO) {
            (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs()).distinct()
        }

        return uuids.mapNotNull { resolveProfile(it) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active)) {
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    override suspend fun queryActiveSelectionSnapshot(): ProfileSelectionSnapshot? {
        val uuid = store.activeProfile ?: return null
        if (!ImportedDao().exists(uuid)) return null
        val choices = SelectionDao().querySelections(uuid).mapNotNull { row ->
            val group = row.proxy.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val selected = row.selected.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProfileSelectorChoice(group, selected)
        }
        return ProfileSelectionSnapshot(uuid, choices)
    }

    override suspend fun setSelected(uuid: UUID, group: String, name: String): Boolean {
        if (group.isBlank() || name.isBlank()) return false
        if (!ImportedDao().exists(uuid)) return false
        SelectionDao().setSelected(Selection(uuid, group, name))
        return true
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)

        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = pending?.upload ?: imported?.upload ?: return null
        val download = pending?.download ?: imported?.download ?: return null
        val total = pending?.total ?: imported?.total ?: return null
        val expire = pending?.expire ?: imported?.expire ?: return null

        return Profile(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            active = active != null && imported?.uuid == active,
            interval = interval,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            updatedAt = resolveUpdatedAt(uuid),
            imported = imported != null,
            pending = pending != null,
            ageSecretKey = if (pending != null) pending.ageSecretKey else imported?.ageSecretKey,
            tag = imported?.tag,
            status = imported?.status,
            deviceLimit = imported?.deviceLimit,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        return context.pendingDir.resolve(uuid.toString()).directoryLastModified
            ?: context.importedDir.resolve(uuid.toString()).directoryLastModified
            ?: -1
    }

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists())
            throw FileNotFoundException("profile $source not found")

        t.deleteRecursively()

        s.copyRecursively(t)
    }
}
