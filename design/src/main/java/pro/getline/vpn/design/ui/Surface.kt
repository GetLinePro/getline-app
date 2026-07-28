package pro.getline.vpn.design.ui

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import pro.getline.vpn.design.BR

class Surface : BaseObservable() {
    var insets: Insets = Insets.EMPTY
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.insets)
        }
}