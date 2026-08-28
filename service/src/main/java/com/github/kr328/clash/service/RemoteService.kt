package com.github.kr328.clash.service

import android.content.Intent
import android.os.IBinder
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILocalLanProxyRuntime
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.wrap
import com.github.kr328.clash.service.util.cancelAndJoinBlocking

class RemoteService : BaseService(), IRemoteService {
    private val binder = this.wrap()

    private var clash: ClashManager? = null
    private var profile: ProfileManager? = null
    private var localLanProxy: LocalLanProxyRuntimeManager? = null
    private var clashBinder: IClashManager? = null
    private var profileBinder: IProfileManager? = null
    private var localLanProxyBinder: ILocalLanProxyRuntime? = null

    override fun onCreate() {
        super.onCreate()

        clash = ClashManager(this)
        profile = ProfileManager(this)
        localLanProxy = LocalLanProxyRuntimeManager()
        clashBinder = clash?.wrap() as IClashManager?
        profileBinder = profile?.wrap() as IProfileManager?
        localLanProxyBinder = localLanProxy?.wrap() as ILocalLanProxyRuntime?
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

    override fun localLanProxy(): ILocalLanProxyRuntime {
        return localLanProxyBinder!!
    }
}