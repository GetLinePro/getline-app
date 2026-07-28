package pro.getline.vpn.getline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.getline.vpn.core.model.FetchStatus
import pro.getline.vpn.design.model.GetLineFetchStatusCopy

class GetLineFetchStatusCopyTest {
    @Test
    fun fetchConfiguration_mapsToLoadingConfig() {
        assertEquals(
            GetLineFetchStatusCopy.Stage.LoadingConfig,
            GetLineFetchStatusCopy.stageOf(FetchStatus.Action.FetchConfiguration),
        )
    }

    @Test
    fun fetchProviders_mapsToLoadingConfig_notProviderName() {
        // User must never see "microsoft" / raw provider labels from this stage.
        assertEquals(
            GetLineFetchStatusCopy.Stage.LoadingConfig,
            GetLineFetchStatusCopy.stageOf(FetchStatus.Action.FetchProviders),
        )
    }

    @Test
    fun verifying_mapsToChecking() {
        assertEquals(
            GetLineFetchStatusCopy.Stage.Checking,
            GetLineFetchStatusCopy.stageOf(FetchStatus.Action.Verifying),
        )
    }

    @Test
    fun subscriptionInfo_hasNoUserCopy() {
        assertNull(GetLineFetchStatusCopy.stageOf(FetchStatus.Action.SubscriptionInfo))
    }

    @Test
    fun diagnosticLine_keepsRawArgsForLogs() {
        val status = FetchStatus(
            action = FetchStatus.Action.FetchProviders,
            args = listOf("microsoft"),
            progress = 1,
            max = 3,
        )
        val line = GetLineFetchStatusCopy.diagnosticLine(status)
        assert(line.contains("microsoft"))
        assert(line.contains("FetchProviders"))
        assert(line.contains("1/3"))
    }
}
