package com.github.kr328.clash.service.localproxy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.constants.Intents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Write-before-notify is the whole point of routing every write through
 * [LocalLanProxyRuntimeRegistry.publish]: the broadcast carries no payload, so
 * a receiver that reads the registry and finds the *previous* value has no
 * second chance to learn about the change — the next signal may never come.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalLanProxyRuntimeRegistryTest {
    private lateinit var context: Application

    private val observed = mutableListOf<LocalLanProxyRuntimeState>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            observed += LocalLanProxyRuntimeRegistry.state
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Global.init(context)

        LocalLanProxyRuntimeRegistry.reset(context)
        drain()
        observed.clear()

        context.registerReceiver(receiver, IntentFilter(Intents.ACTION_LOCAL_LAN_PROXY_CHANGED))
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        LocalLanProxyRuntimeRegistry.reset(context)
        drain()
    }

    private fun drain() = shadowOf(context.mainLooper).idle()

    @Test
    fun publishedStateIsReadableBeforeItsSignalArrives() {
        val active = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234)

        LocalLanProxyRuntimeRegistry.publish(context, active)
        drain()

        assertEquals(listOf<LocalLanProxyRuntimeState>(active), observed)
        assertEquals(active, LocalLanProxyRuntimeRegistry.state)
    }

    @Test
    fun resetIsPublishedAndSignalled() {
        val active = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234)

        LocalLanProxyRuntimeRegistry.publish(context, active)
        drain()
        LocalLanProxyRuntimeRegistry.reset(context)
        drain()

        // Drained between the two writes, so each delivery is answered with
        // the value that caused it rather than with a later one.
        assertEquals(listOf(active, LocalLanProxyRuntimeState.Inactive), observed)
        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeRegistry.state)
    }

    @Test
    fun burstIsAnsweredWithTheNewestValue() {
        val active = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234)

        // Undrained: both signals land after both writes. Because the
        // broadcast carries no payload, a reader that arrives late still reads
        // the truth instead of replaying a stale one — the reason the value
        // does not travel in the intent.
        LocalLanProxyRuntimeRegistry.publish(context, active)
        LocalLanProxyRuntimeRegistry.reset(context)
        drain()

        assertEquals(
            listOf(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeState.Inactive),
            observed,
        )
        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeRegistry.state)
    }

    @Test
    fun repeatedValueDoesNotWakeReaders() {
        val active = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234)

        LocalLanProxyRuntimeRegistry.publish(context, active)
        drain()
        observed.clear()

        LocalLanProxyRuntimeRegistry.publish(context, active.copy())
        drain()

        assertEquals(emptyList<LocalLanProxyRuntimeState>(), observed)
        assertEquals(active, LocalLanProxyRuntimeRegistry.state)
    }
}
