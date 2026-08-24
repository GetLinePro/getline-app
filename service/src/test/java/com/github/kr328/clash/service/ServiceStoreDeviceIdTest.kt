package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.store.ServiceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceStoreDeviceIdTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearDeviceId() {
        Global.init(RuntimeEnvironment.getApplication())
        ServiceStore(context).deviceId = ""
    }

    @Test
    fun emptyStore_generatesAndPersistsUuid() {
        val store = ServiceStore(context)
        val generated = store.getOrCreateDeviceId()

        assertTrue(generated.isNotBlank())
        assertTrue(REMNAWAVE_HWID.matches(generated))
        assertEquals(generated, store.deviceId)
        assertEquals(generated, ServiceStore(context).deviceId)
    }

    @Test
    fun secondCall_returnsPersistedValue() {
        val store = ServiceStore(context)
        val first = store.getOrCreateDeviceId()
        val second = store.getOrCreateDeviceId()

        assertEquals(first, second)
        assertEquals(first, ServiceStore(context).getOrCreateDeviceId())
    }

    @Test
    fun regenerateAfterClear_yieldsDifferentId() {
        val store = ServiceStore(context)
        val first = store.getOrCreateDeviceId()
        store.deviceId = ""
        val second = store.getOrCreateDeviceId()

        assertTrue(REMNAWAVE_HWID.matches(first))
        assertTrue(REMNAWAVE_HWID.matches(second))
        assertNotEquals(first, second)
    }

    private companion object {
        val REMNAWAVE_HWID = Regex("^[a-zA-Z0-9=-]{10,64}$")
    }
}
