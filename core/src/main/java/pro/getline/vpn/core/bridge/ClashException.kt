package pro.getline.vpn.core.bridge

import androidx.annotation.Keep

@Keep
class ClashException(msg: String) : IllegalArgumentException(msg)