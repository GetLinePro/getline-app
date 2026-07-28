package pro.getline.vpn.design.model

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import pro.getline.vpn.core.model.Provider
import pro.getline.vpn.design.BR

class ProviderState(
    val provider: Provider,
    updatedAt: Long,
    updating: Boolean,
) : BaseObservable() {
    var updatedAt: Long = updatedAt
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.updatedAt)
        }

    var updating: Boolean = updating
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.updating)
        }
}