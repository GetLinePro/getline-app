package pro.getline.vpn.service.remote

import pro.getline.vpn.core.model.LogMessage
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface ILogObserver {
    fun newItem(log: LogMessage)
}