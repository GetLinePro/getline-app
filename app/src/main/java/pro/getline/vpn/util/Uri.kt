package pro.getline.vpn.util

import android.net.Uri

val Uri.fileName: String?
    get() = schemeSpecificPart.split("/").lastOrNull()