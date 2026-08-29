package com.github.kr328.clash.service.localproxy

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The projection crosses a process boundary as a `Bundle`, so the encoding is
 * the contract between the coordinator and the facade. What these pin down is
 * the direction each malformed case fails in: never "active at an endpoint the
 * reader cannot dial".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalLanProxyRuntimeStateTest {
    private fun roundTrip(state: LocalLanProxyRuntimeState): LocalLanProxyRuntimeState =
        LocalLanProxyRuntimeState.fromBundle(state.toBundle())

    @Test
    fun active_survivesRoundTrip() {
        val state = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234)

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun inactive_survivesRoundTrip() {
        assertEquals(LocalLanProxyRuntimeState.Inactive, roundTrip(LocalLanProxyRuntimeState.Inactive))
    }

    @Test
    fun missingBundle_readsAsInactive() {
        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeState.fromBundle(null))
    }

    @Test
    fun activeWithoutAddress_readsAsInactive() {
        val bundle = Bundle().apply {
            putBoolean("active", true)
            putInt("port", 1234)
        }

        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeState.fromBundle(bundle))
    }

    @Test
    fun activeWithUnusablePort_readsAsInactive() {
        val bundle = Bundle().apply {
            putBoolean("active", true)
            putString("address", "10.135.213.166")
            putInt("port", 0)
        }

        assertEquals(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeState.fromBundle(bundle))
    }

    @Test
    fun credentialsNeverCross() {
        val bundle = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234).toBundle()

        assertEquals(setOf("active", "address", "port"), bundle.keySet())
    }
}
