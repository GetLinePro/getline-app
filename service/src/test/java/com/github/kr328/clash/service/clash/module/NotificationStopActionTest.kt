package com.github.kr328.clash.service.clash.module

import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.constants.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationStopActionTest {
    @Test
    fun stopAction_requestsClashStop() {
        val context = RuntimeEnvironment.getApplication()

        Global.init(context)

        val notification = NotificationCompat.Builder(context, "test")
            .addStopAction(context)
            .build()

        val action = NotificationCompat.getAction(notification, 0)!!

        val shadow = Shadows.shadowOf(action.actionIntent)

        assertTrue(shadow.isBroadcast)
        assertEquals(Intents.ACTION_CLASH_REQUEST_STOP, shadow.savedIntent.action)
        assertEquals(context.packageName, shadow.savedIntent.`package`)
    }
}
