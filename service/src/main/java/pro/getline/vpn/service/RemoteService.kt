package pro.getline.vpn.service

import android.content.Intent
import android.os.IBinder
import pro.getline.vpn.service.remote.IClashManager
import pro.getline.vpn.service.remote.IRemoteService
import pro.getline.vpn.service.remote.IProfileManager
import pro.getline.vpn.service.remote.wrap
import pro.getline.vpn.service.util.cancelAndJoinBlocking

class RemoteService : BaseService(), IRemoteService {
    private val binder = this.wrap()

    private var clash: ClashManager? = null
    private var profile: ProfileManager? = null
    private var clashBinder: IClashManager? = null
    private var profileBinder: IProfileManager? = null

    override fun onCreate() {
        super.onCreate()

        clash = ClashManager(this)
        profile = ProfileManager(this)
        clashBinder = clash?.wrap() as IClashManager?
        profileBinder = profile?.wrap() as IProfileManager?
    }

    override fun onDestroy() {
        super.onDestroy()

        clash?.cancelAndJoinBlocking()
        profile?.cancelAndJoinBlocking()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun clash(): IClashManager {
        return clashBinder!!
    }

    override fun profile(): IProfileManager {
        return profileBinder!!
    }
}