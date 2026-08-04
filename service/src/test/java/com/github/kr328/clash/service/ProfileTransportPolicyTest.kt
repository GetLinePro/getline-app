package com.github.kr328.clash.service

import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTransportPolicyTest {
    @Test
    fun httpsUrl_usesPlatformTransport() {
        assertTrue(shouldUsePlatformPrimaryConfigTransport(Profile.Type.Url, "https"))
        assertTrue(shouldUsePlatformPrimaryConfigTransport(Profile.Type.Url, "HTTPS"))
    }

    @Test
    fun nonHttpsUrl_keepsNativeTransport() {
        assertFalse(shouldUsePlatformPrimaryConfigTransport(Profile.Type.Url, "http"))
        assertFalse(shouldUsePlatformPrimaryConfigTransport(Profile.Type.Url, "content"))
        assertFalse(shouldUsePlatformPrimaryConfigTransport(Profile.Type.Url, null))
    }

    @Test
    fun nonUrlProfile_keepsNativeTransport_evenWithHttpsSource() {
        assertFalse(shouldUsePlatformPrimaryConfigTransport(Profile.Type.File, "https"))
        assertFalse(shouldUsePlatformPrimaryConfigTransport(Profile.Type.External, "https"))
    }
}
