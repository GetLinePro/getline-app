package pro.getline.vpn.cmfa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.github.kr328.clash.core.model.FetchStatus
import pro.getline.vpn.getlineui.model.GetLineImportStage

class GetLineImportStageMappingTest {
    @Test
    fun fetchConfiguration_mapsToLoadingConfig() {
        assertEquals(
            GetLineImportStage.LoadingConfig,
            mapFetchActionToImportStage(FetchStatus.Action.FetchConfiguration),
        )
    }

    @Test
    fun fetchProviders_mapsToLoadingConfig_notProviderName() {
        // User must never see "microsoft" / raw provider labels from this stage.
        assertEquals(
            GetLineImportStage.LoadingConfig,
            mapFetchActionToImportStage(FetchStatus.Action.FetchProviders),
        )
    }

    @Test
    fun verifying_mapsToChecking() {
        assertEquals(
            GetLineImportStage.Checking,
            mapFetchActionToImportStage(FetchStatus.Action.Verifying),
        )
    }

    @Test
    fun subscriptionInfo_hasNoUserCopy() {
        assertNull(mapFetchActionToImportStage(FetchStatus.Action.SubscriptionInfo))
    }

    @Test
    fun diagnosticLine_keepsRawArgsForLogs() {
        val status = FetchStatus(
            action = FetchStatus.Action.FetchProviders,
            args = listOf("microsoft"),
            progress = 1,
            max = 3,
        )
        val line = fetchStatusDiagnosticLine(status)
        assert(line.contains("microsoft"))
        assert(line.contains("FetchProviders"))
        assert(line.contains("1/3"))
    }
}
