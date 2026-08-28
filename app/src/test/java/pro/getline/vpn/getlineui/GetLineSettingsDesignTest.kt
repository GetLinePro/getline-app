package pro.getline.vpn.getlineui

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Settings screen as a renderer: given a state, what is on screen and what
 * a tap reports. It never learns where the state came from — that seam is the
 * point of the class — so these tests hand it states directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GetLineSettingsDesignTest {
    private val context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.GetLineTheme,
    )

    private val design = GetLineSettingsDesign(context)

    private val ready = GetLineSettingsDesign.State(
        status = GetLineSettingsDesign.Status.Disabled,
        port = "28451",
        username = "getline",
        password = "s3cret-value",
        actionEnabled = true,
    )

    private val active = ready.copy(
        status = GetLineSettingsDesign.Status.Active,
        address = "192.168.1.24",
        hint = GetLineSettingsDesign.Hint.LockedWhileActive,
    )

    private fun render(state: GetLineSettingsDesign.State) = runBlocking { design.render(state) }

    private fun view(id: Int): View = design.root.findViewById(id)

    private fun text(id: Int): String = view(id).let { (it as TextView).text.toString() }

    private fun string(id: Int): String = context.getString(id)

    private fun nextRequest(): GetLineSettingsDesign.Request? =
        design.requests.tryReceive().getOrNull()

    @Test
    fun beforeTheFirstStateThereAreNoValuesToLookAt() {
        assertEquals(
            string(R.string.get_line_settings_status_loading),
            text(R.id.settings_proxy_status),
        )
        assertEquals(View.GONE, view(R.id.settings_proxy_fields).visibility)
        assertEquals(false, view(R.id.settings_proxy_action).isEnabled)
    }

    @Test
    fun theSavedSettingsAreShownAndTheActionOffersTurningOn() {
        render(ready)

        assertEquals("28451", text(R.id.settings_proxy_port))
        assertEquals("getline", text(R.id.settings_proxy_username))
        assertEquals(View.GONE, view(R.id.settings_proxy_address_row).visibility)
        assertEquals(
            string(R.string.get_line_settings_enable),
            (view(R.id.settings_proxy_action) as MaterialButton).text.toString(),
        )
        assertEquals(true, view(R.id.settings_proxy_action).isEnabled)
        assertEquals(View.GONE, view(R.id.settings_proxy_hint).visibility)
    }

    @Test
    fun aBoundListenerShowsItsAddressAndOffersTurningOff() {
        render(active)

        assertEquals(View.VISIBLE, view(R.id.settings_proxy_address_row).visibility)
        assertEquals("192.168.1.24", text(R.id.settings_proxy_address))
        assertEquals(
            string(R.string.get_line_settings_disable),
            (view(R.id.settings_proxy_action) as MaterialButton).text.toString(),
        )
        assertEquals(View.VISIBLE, view(R.id.settings_proxy_hint).visibility)
        assertEquals(
            string(R.string.get_line_settings_locked_while_active),
            text(R.id.settings_proxy_hint),
        )
    }

    /**
     * The credentials a bound listener authenticates with cannot be changed
     * under it, so the rows that would edit them do not respond at all.
     */
    @Test
    fun editingIsRefusedWhileBound() {
        render(active)

        listOf(
            R.id.settings_proxy_port_row,
            R.id.settings_proxy_username_row,
            R.id.settings_proxy_password_row,
        ).forEach { id ->
            assertEquals(false, view(id).isClickable)

            view(id).performClick()
        }

        assertNull(nextRequest())
    }

    /**
     * Loading is not "not running": until the screen has been told what the
     * session holds, editing the values a listener may be using is exactly the
     * mistake the lock exists to prevent.
     */
    @Test
    fun editingIsRefusedBeforeTheFirstAnswer() {
        render(ready.copy(status = GetLineSettingsDesign.Status.Loading))

        assertEquals(false, view(R.id.settings_proxy_port_row).isClickable)

        design.editPort()

        assertNull(nextRequest())
    }

    @Test
    fun thePasswordIsMaskedUntilItIsRevealed() {
        render(ready)

        val masked = text(R.id.settings_proxy_password)
        assertNotEquals("s3cret-value", masked)

        view(R.id.settings_reveal_password).performClick()
        assertEquals("s3cret-value", text(R.id.settings_proxy_password))

        view(R.id.settings_reveal_password).performClick()
        assertEquals(masked, text(R.id.settings_proxy_password))
    }

    /** A revealed password is revealed; the next one is a different secret. */
    @Test
    fun aNewPasswordComesBackMasked() {
        render(ready)
        view(R.id.settings_reveal_password).performClick()
        assertEquals("s3cret-value", text(R.id.settings_proxy_password))

        render(ready.copy(password = "regenerated"))

        assertNotEquals("regenerated", text(R.id.settings_proxy_password))
    }

    @Test
    fun leavingTheScreenRemasksIt() {
        render(ready)
        view(R.id.settings_reveal_password).performClick()

        runBlocking { design.hidePassword() }

        assertNotEquals("s3cret-value", text(R.id.settings_proxy_password))
    }

    /**
     * Re-rendering the same state must not undo the reveal: the facade
     * republishes on every invalidation, and a proxy coming up would otherwise
     * hide the password while the user was reading it.
     */
    @Test
    fun anUnrelatedStateChangeLeavesTheRevealAlone() {
        render(ready)
        view(R.id.settings_reveal_password).performClick()

        render(ready.copy(status = GetLineSettingsDesign.Status.Enabling, actionEnabled = false))

        assertEquals("s3cret-value", text(R.id.settings_proxy_password))
    }

    @Test
    fun eachFieldReportsItsOwnCopy() {
        render(active)

        view(R.id.settings_copy_address).performClick()
        assertEquals(
            GetLineSettingsDesign.Request.Copy(GetLineSettingsDesign.Field.Address),
            nextRequest(),
        )

        view(R.id.settings_copy_port).performClick()
        assertEquals(
            GetLineSettingsDesign.Request.Copy(GetLineSettingsDesign.Field.Port),
            nextRequest(),
        )

        view(R.id.settings_copy_username).performClick()
        assertEquals(
            GetLineSettingsDesign.Request.Copy(GetLineSettingsDesign.Field.Username),
            nextRequest(),
        )

        view(R.id.settings_copy_password).performClick()
        assertEquals(
            GetLineSettingsDesign.Request.Copy(GetLineSettingsDesign.Field.Password),
            nextRequest(),
        )
    }

    /** Copying stays available while the values are locked for editing. */
    @Test
    fun copyingWorksWhileBound() {
        render(active)

        assertEquals(true, view(R.id.settings_copy_password).isClickable)
    }

    @Test
    fun theActionReportsWhatTheStatusImplies() {
        render(ready)
        view(R.id.settings_proxy_action).performClick()
        assertEquals(GetLineSettingsDesign.Request.Enable, nextRequest())

        render(active)
        view(R.id.settings_proxy_action).performClick()
        assertEquals(GetLineSettingsDesign.Request.Disable, nextRequest())
    }

    /**
     * A disable that is still running keeps offering "turn off": flipping the
     * button back to "turn on" mid-teardown would invite a second, conflicting
     * request.
     */
    @Test
    fun aDisableInFlightStillReadsAsTurningOff() {
        render(active.copy(status = GetLineSettingsDesign.Status.Disabling, actionEnabled = false))

        assertEquals(
            string(R.string.get_line_settings_disable),
            (view(R.id.settings_proxy_action) as MaterialButton).text.toString(),
        )
    }

    @Test
    fun backIsReported() {
        view(R.id.settings_back).performClick()

        assertEquals(GetLineSettingsDesign.Request.Back, nextRequest())
    }

    @Test
    fun anUnusableStoreLeavesNothingToRead() {
        render(
            GetLineSettingsDesign.State(
                status = GetLineSettingsDesign.Status.Disabled,
                hint = GetLineSettingsDesign.Hint.StorageUnavailable,
            ),
        )

        assertEquals(View.GONE, view(R.id.settings_proxy_fields).visibility)
        assertEquals(
            string(R.string.get_line_settings_storage_unavailable),
            text(R.id.settings_proxy_hint),
        )
    }
}
