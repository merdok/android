package io.panelassistant.android

import android.content.Intent
import io.panelassistant.android.DashboardRendererRig.Companion.HA
import io.panelassistant.android.control.BuiltinDashboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only a navigate, an explicit reload or a configuration change is consent to try again. A plain return
 * to the foreground (the kiosk and watchdog return loops relaunch the dashboard whenever it is behind
 * something, and HOME does the same) is not, so it must leave the renderer as it found it unless the page
 * is under the reconnecting cover, which only a fresh load replaces.
 */
class DashboardRendererConsentTest {
    private val rig = DashboardRendererRig()

    @After fun release() = rig.close()

    /**
     * A refused login stays refused until someone changes something or asks for a reload. A plain return
     * to the foreground used to clear the latch and ask Home Assistant again with the same refused
     * credential, once per backgrounding.
     */
    @Test fun aPlainReturnToTheForegroundLeavesARefusedLoginLatched() {
        val page = RecordingWebView("data:text/html,")
        rig.show(page)
        rig.admitted()
        rig.phase = RendererPhase.AUTH_LATCHED
        BuiltinDashboard.setActivityAuthLatched(rig.owner, true)

        rig.relaunch(Intent())

        assertTrue("the refused login is still reported", BuiltinDashboard.authLatched)
        assertEquals("the latch page was left alone", emptyList<String>(), page.calls)
    }

    /**
     * A load already in flight belongs to the handshake watchdog. A plain return to the foreground used to
     * restart it, which is also how a burst of setting changes, each relaunching the dashboard, loaded the
     * same page again and again.
     */
    @Test fun aPlainReturnToTheForegroundDoesNotRestartALoadInFlight() {
        val page = RecordingWebView("$HA/lovelace")
        rig.show(page)
        rig.admitted()
        rig.phase = RendererPhase.LOADING

        rig.relaunch(Intent())

        assertEquals("the load in flight was left alone", emptyList<String>(), page.calls)
    }
}
