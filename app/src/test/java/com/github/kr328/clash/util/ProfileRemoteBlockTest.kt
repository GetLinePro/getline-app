package com.github.kr328.clash.util

import android.os.DeadObjectException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileRemoteBlockTest {

    @Test
    fun retryOnDeadObject_reentersUntilSuccess() = runBlocking {
        var calls = 0
        var resets = 0

        val result = runProfileRemoteBlock(
            retryOnDeadObject = true,
            onDeadObject = { resets++ },
        ) {
            calls++
            if (calls == 1) {
                throw DeadObjectException("first attempt")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(1, resets)
    }

    @Test
    fun oneShot_resetsThenThrowsBinderDied_withoutReentering() = runBlocking {
        var calls = 0
        var resets = 0

        try {
            runProfileRemoteBlock(
                retryOnDeadObject = false,
                onDeadObject = { resets++ },
            ) {
                calls++
                throw DeadObjectException("died")
            }
            fail("expected BinderDiedException")
        } catch (_: BinderDiedException) {
            // expected
        }

        assertEquals(1, calls)
        assertEquals(1, resets)
    }
}
