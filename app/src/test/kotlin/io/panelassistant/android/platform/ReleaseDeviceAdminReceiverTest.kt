package io.panelassistant.android.platform

import android.content.ContextWrapper
import android.content.Intent
import io.panelassistant.android.control.FakeScreenPower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Android refuses to uninstall an active device admin and the panel's shell cannot remove one, so
 * Panel Assistant asks the app with a broadcast before uninstalling it. The app must give it up.
 */
class ReleaseDeviceAdminReceiverTest {
    @Test fun anActiveDeviceAdminIsGivenUpWhenPanelAssistantAsks() {
        val power = FakeScreenPower().apply { adminActive = true }

        ReleaseDeviceAdminReceiver { power }.onReceive(ContextWrapper(null), Intent())

        assertFalse(power.isDeviceAdminActive())
        assertEquals(false, power.lockNow())
    }
}
