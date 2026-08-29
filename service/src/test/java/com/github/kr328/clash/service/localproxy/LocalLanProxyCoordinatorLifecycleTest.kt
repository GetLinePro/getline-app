package com.github.kr328.clash.service.localproxy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.clash.module.ConfigurationReloadPort
import com.github.kr328.clash.service.clash.module.ConfigurationReloadResult
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeConfig
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A binder command can outlive the session it was aimed at: the manager
 * resolves the coordinator, then `TunService` tears down while the call is
 * still on its way in. Refusing it is not politeness — starting a transaction
 * then means binding a credentialed listener into a Clash runtime that is
 * already stopping, and nothing would be watching its address afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalLanProxyCoordinatorLifecycleTest {
    class HostService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private class RecordingEndpointSource : LocalLanProxyEndpointSource {
        var queries = 0

        override suspend fun currentEndpoint(): LocalLanProxyEndpoint? {
            queries++

            return null
        }

        override val changes: Flow<Unit> = emptyFlow()
    }

    private val endpoints = RecordingEndpointSource()

    private val reloadPort = object : ConfigurationReloadPort {
        override suspend fun reloadAndAwait(): ConfigurationReloadResult =
            error("a closed session must never reach a reload")
    }

    private lateinit var coordinator: LocalLanProxyRuntimeCoordinator

    @Before
    fun setUp() {
        Global.init(RuntimeEnvironment.getApplication())

        val service = Robolectric.buildService(HostService::class.java).create().get()

        coordinator = LocalLanProxyRuntimeCoordinator(
            service = service,
            reloadPort = reloadPort,
            endpointSource = endpoints,
            protect = { true },
        ).apply { start() }
    }

    @Test
    fun enableArrivingAfterCloseIsRefusedBeforeAnythingIsTouched() = runBlocking {
        coordinator.close()

        val result = coordinator.enable(
            LocalLanProxyRuntimeConfig(port = 1234, username = "getline", password = "secret"),
        )

        assertEquals(LocalLanProxyRuntimeResult.Status.VpnUnavailable, result.status)
        assertEquals("the endpoint source must not be consulted after close", 0, endpoints.queries)
        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeRegistry.state)
    }

    @Test
    fun disableArrivingAfterCloseIsRefusedToo() = runBlocking {
        coordinator.close()

        val result = coordinator.disable()

        assertEquals(LocalLanProxyRuntimeResult.Status.VpnUnavailable, result.status)
        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeRegistry.state)
    }

    @Test
    fun theSessionEndsWithNothingReportedAsBound() = runBlocking {
        coordinator.close()

        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeRegistry.state)
    }
}
