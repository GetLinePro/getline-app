package pro.getline.vpn.design.util

import pro.getline.vpn.design.view.ObservableScrollView

val ObservableScrollView.isTop: Boolean
    get() = scrollX == 0 && scrollY == 0
