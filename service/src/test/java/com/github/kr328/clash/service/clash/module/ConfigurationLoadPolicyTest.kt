package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationLoadPolicyTest {
    @Test
    fun firstLoadFailure_stopsRuntime() {
        assertTrue(ConfigurationLoadPolicy.abortRuntime(hasSuccessfulLoad = false))
    }

    @Test
    fun reloadFailure_keepsRuntime() {
        assertFalse(ConfigurationLoadPolicy.abortRuntime(hasSuccessfulLoad = true))
    }
}
