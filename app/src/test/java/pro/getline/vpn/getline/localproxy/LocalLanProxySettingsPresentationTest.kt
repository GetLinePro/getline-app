package pro.getline.vpn.getline.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pro.getline.vpn.getlineui.GetLineSettingsDesign
import pro.getline.vpn.getlineui.R as GetLineUiR

class LocalLanProxySettingsPresentationTest {
    private val config = LocalLanProxyUserConfig(
        port = 28451,
        username = "getline",
        password = "s3cret-value",
    )

    private fun present(snapshot: LocalLanProxySnapshot) =
        LocalLanProxySettingsPresentation.present(snapshot)

    @Test
    fun beforeTheFirstAnswerNothingIsOfferedAndNothingIsShown() {
        val state = present(LocalLanProxySnapshot())

        assertEquals(GetLineSettingsDesign.Status.Loading, state.status)
        assertNull(state.port)
        assertNull(state.address)
        assertEquals(false, state.actionEnabled)
        assertEquals(GetLineSettingsDesign.Hint.None, state.hint)
    }

    @Test
    fun withTheVpnRunningTheSavedSettingsAreShownAndTurningOnIsOffered() {
        val state = present(
            LocalLanProxySnapshot(
                status = LocalLanProxyStatus.Disabled,
                availability = LocalLanProxyAvailability.Ready,
                config = config,
            ),
        )

        assertEquals(GetLineSettingsDesign.Status.Disabled, state.status)
        assertEquals("28451", state.port)
        assertEquals("getline", state.username)
        assertEquals("s3cret-value", state.password)
        assertNull(state.address)
        assertEquals(true, state.actionEnabled)
        assertEquals(GetLineSettingsDesign.Hint.None, state.hint)
    }

    /** Editing is allowed with the VPN off; only turning the proxy on is not. */
    @Test
    fun withTheVpnOffTheSettingsRemainButTheActionIsRefusedWithAReason() {
        val state = present(
            LocalLanProxySnapshot(
                status = LocalLanProxyStatus.Disabled,
                availability = LocalLanProxyAvailability.VpnOffline,
                config = config,
            ),
        )

        assertEquals("28451", state.port)
        assertEquals(false, state.actionEnabled)
        assertEquals(GetLineSettingsDesign.Hint.ConnectVpnFirst, state.hint)
    }

    @Test
    fun whileBoundTheLiveEndpointIsShownAndTurningOffStaysAvailable() {
        val state = present(
            LocalLanProxySnapshot(
                status = LocalLanProxyStatus.Active(address = "192.168.1.24", port = 28451),
                availability = LocalLanProxyAvailability.Ready,
                config = config,
            ),
        )

        assertEquals(GetLineSettingsDesign.Status.Active, state.status)
        assertEquals("192.168.1.24", state.address)
        assertEquals("28451", state.port)
        assertEquals(true, state.actionEnabled)
        assertEquals(GetLineSettingsDesign.Hint.LockedWhileActive, state.hint)
    }

    /**
     * The bound port is the one that matters while a listener is up: the saved
     * value cannot be edited in that state, and showing it instead would let
     * the row disagree with the address beside it.
     */
    @Test
    fun aBoundListenerDescribesItselfEvenIfTheSavedPortDiffers() {
        val state = present(
            LocalLanProxySnapshot(
                status = LocalLanProxyStatus.Active(address = "192.168.1.24", port = 9999),
                availability = LocalLanProxyAvailability.Ready,
                config = config,
            ),
        )

        assertEquals("9999", state.port)
    }

    /** A listener that is up can always be turned off, whatever the store says. */
    @Test
    fun turningOffAnActiveListenerSurvivesAnUnreadableStore() {
        val state = present(
            LocalLanProxySnapshot(
                status = LocalLanProxyStatus.Active(address = "192.168.1.24", port = 28451),
                availability = LocalLanProxyAvailability.SettingsUnavailable,
                config = null,
            ),
        )

        assertEquals(true, state.actionEnabled)
        assertEquals(GetLineSettingsDesign.Hint.StorageUnavailable, state.hint)
    }

    @Test
    fun transitionsOfferNoActionAtAll() {
        listOf(LocalLanProxyStatus.Enabling, LocalLanProxyStatus.Disabling).forEach { status ->
            val state = present(
                LocalLanProxySnapshot(
                    status = status,
                    availability = LocalLanProxyAvailability.Ready,
                    config = config,
                ),
            )

            assertEquals(false, state.actionEnabled)
        }
    }

    @Test
    fun anUnusableStoreSaysSoAndOffersNothingToTurnOn() {
        val state = present(
            LocalLanProxySnapshot(
                status = LocalLanProxyStatus.Disabled,
                availability = LocalLanProxyAvailability.SettingsUnavailable,
                config = null,
            ),
        )

        assertNull(state.port)
        assertEquals(false, state.actionEnabled)
        assertEquals(GetLineSettingsDesign.Hint.StorageUnavailable, state.hint)
    }

    /** What gets copied is the secret, never the mask the row draws. */
    @Test
    fun copyTakesTheValueFromTheSnapshot() {
        val snapshot = LocalLanProxySnapshot(
            status = LocalLanProxyStatus.Active(address = "192.168.1.24", port = 28451),
            availability = LocalLanProxyAvailability.Ready,
            config = config,
        )

        assertEquals(
            "192.168.1.24",
            LocalLanProxySettingsPresentation.copyValue(snapshot, GetLineSettingsDesign.Field.Address),
        )
        assertEquals(
            "28451",
            LocalLanProxySettingsPresentation.copyValue(snapshot, GetLineSettingsDesign.Field.Port),
        )
        assertEquals(
            "getline",
            LocalLanProxySettingsPresentation.copyValue(snapshot, GetLineSettingsDesign.Field.Username),
        )
        assertEquals(
            "s3cret-value",
            LocalLanProxySettingsPresentation.copyValue(snapshot, GetLineSettingsDesign.Field.Password),
        )
    }

    @Test
    fun thereIsNothingToCopyBeforeTheSettingsAreKnown() {
        val empty = LocalLanProxySnapshot()

        GetLineSettingsDesign.Field.values().forEach { field ->
            assertNull(LocalLanProxySettingsPresentation.copyValue(empty, field))
        }
    }

    @Test
    fun anEditedFieldReplacesOnlyItself() {
        assertEquals(
            config.copy(port = 1080),
            LocalLanProxySettingsPresentation.edited(config, GetLineSettingsDesign.Field.Port, " 1080 "),
        )
        assertEquals(
            config.copy(username = "laptop"),
            LocalLanProxySettingsPresentation.edited(config, GetLineSettingsDesign.Field.Username, "laptop"),
        )
        assertEquals(
            config.copy(password = "another"),
            LocalLanProxySettingsPresentation.edited(config, GetLineSettingsDesign.Field.Password, "another"),
        )
    }

    /**
     * Out-of-range is still a config — the facade owns that rule and returns
     * the field it rejected. Only text that cannot become a port at all stops
     * here.
     */
    @Test
    fun aPortThatIsNotANumberCannotBeCarriedButAnInvalidOneIs() {
        assertNull(
            LocalLanProxySettingsPresentation.edited(config, GetLineSettingsDesign.Field.Port, "eighty"),
        )
        assertEquals(
            config.copy(port = 80),
            LocalLanProxySettingsPresentation.edited(config, GetLineSettingsDesign.Field.Port, "80"),
        )
    }

    @Test
    fun theBoundAddressIsNotASetting() {
        assertNull(
            LocalLanProxySettingsPresentation.edited(
                config,
                GetLineSettingsDesign.Field.Address,
                "10.0.0.1",
            ),
        )
    }

    /** Every outcome has a sentence, and success is silent. */
    @Test
    fun everyResultIsRenderable() {
        assertNull(LocalLanProxySettingsPresentation.message(LocalLanProxyResult.Success))

        val results = listOf(
            LocalLanProxyResult.VpnUnavailable,
            LocalLanProxyResult.NoEligibleLan,
            LocalLanProxyResult.PortOccupied,
            LocalLanProxyResult.ActiveNotEditable,
            LocalLanProxyResult.SettingsUnavailable,
            LocalLanProxyResult.ApplyFailed,
            LocalLanProxyResult.SafetyStop,
        ) + LocalLanProxyResult.InvalidSettings.Field.values().map {
            LocalLanProxyResult.InvalidSettings(it)
        }

        results.forEach { result ->
            assertNotNull(result.toString(), LocalLanProxySettingsPresentation.message(result))
        }

        assertEquals(
            results.size,
            results.mapNotNull { LocalLanProxySettingsPresentation.message(it) }.toSet().size,
        )
    }

    @Test
    fun eachFieldHasItsOwnLabel() {
        val labels = GetLineSettingsDesign.Field.values()
            .map { LocalLanProxySettingsPresentation.label(it) }

        assertEquals(labels.size, labels.toSet().size)
        assertEquals(
            GetLineUiR.string.get_line_settings_field_password,
            LocalLanProxySettingsPresentation.label(GetLineSettingsDesign.Field.Password),
        )
    }
}
