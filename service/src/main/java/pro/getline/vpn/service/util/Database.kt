package pro.getline.vpn.service.util

import pro.getline.vpn.service.data.ImportedDao
import pro.getline.vpn.service.data.PendingDao
import java.util.*

suspend fun generateProfileUUID(): UUID {
    var result = UUID.randomUUID()

    while (ImportedDao().exists(result) || PendingDao().exists(result)) {
        result = UUID.randomUUID()
    }

    return result
}
