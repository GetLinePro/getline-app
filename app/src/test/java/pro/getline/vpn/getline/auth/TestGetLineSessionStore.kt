package pro.getline.vpn.getline.auth

import android.content.Context

/** Robolectric has no AndroidKeyStore; production never uses this plaintext test backend. */
internal fun testSessionStore(context: Context): GetLineSessionStore = GetLineSessionStore(
    context = context,
    encryptedPrefsFactory = {
        it.getSharedPreferences(GetLineSessionStore.PREFS_FILE_ENCRYPTED, Context.MODE_PRIVATE)
    },
    encryptedStorageResetter = {
        it.deleteSharedPreferences(GetLineSessionStore.PREFS_FILE_ENCRYPTED)
    },
)
