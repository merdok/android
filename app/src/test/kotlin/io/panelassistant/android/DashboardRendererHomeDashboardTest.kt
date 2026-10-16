package io.panelassistant.android

import io.panelassistant.android.DashboardRendererRig.Companion.HA
import io.panelassistant.android.control.BuiltinDashboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live page does not embody its home dashboard: the path only decides where the page goes. So a home
 * dashboard change on a connected panel, from Home Assistant, a settings page or an import, keeps the page
 * the person is watching and resolves the new home behind it, instead of tearing the dashboard down behind
 * a "Selecting the Home Assistant dashboard" screen once per change.
 */
class DashboardRendererHomeDashboardTest {
    private val rig = DashboardRendererRig()

    @After fun release() = rig.close()

    @Test fun aHomeDashboardChangeAndABurstOfThemKeepTheConnectedPage() {
        val page = RecordingWebView("$HA/lovelace")
        rig.show(page)
        rig.admitted("lovelace")
        rig.phase = RendererPhase.CONNECTED

        for (home in listOf("/kitchen", "", "/kitchen")) {
            rig.config.setHomeDashboard(home)
            // Every home_dashboard writer reaches the renderer as a configuration reconcile with no intent.
            val reconcile = runCatching { rig.relaunch(null) }

            assertEquals("the page was left alone (home '$home')", emptyList<String>(), page.calls)
            assertSame("the same WebView is on screen", page, rig.get("web"))
            assertEquals("admission still stands", HA, rig.get("compatibilityReadyUrl"))
            assertTrue("the reconcile finished (home '$home'): ${reconcile.exceptionOrNull()}", reconcile.isSuccess)
            assertEquals(
                "the new home is being resolved behind the page",
                home,
                (rig.get("homeDashboardCheckingOwner") as HomeDashboardResolutionOwner?)?.configuredPath,
            )
        }
    }

    /**
     * Until the new home's answer lands, the page still shows the old one, so the settings pages must not
     * report the new value as on screen yet.
     */
    @Test fun theNewHomeIsNotReportedOnScreenBeforeItsAnswerLands() {
        rig.show(RecordingWebView("$HA/lovelace"))
        rig.admitted("lovelace")
        rig.phase = RendererPhase.CONNECTED

        rig.config.setHomeDashboard("/kitchen")
        rig.relaunch(null)

        val pending = requireNotNull(BuiltinDashboard.pendingRendererSettings()) { "the renderer publishes a report" }
        assertTrue("the new home is still pending: $pending", "home_dashboard" in pending)
    }

    /**
     * An idle return (or a reload) right after a home change goes to the home the page shows, because the
     * new one is still being resolved. That is no choice of a different page, so the answer, when it lands,
     * must still move the page to the new home; it used to count as a newer navigation and leave the panel on
     * the old home. The answer cannot land on the JVM, so this checks what its handler reads.
     */
    @Test fun goingToTheShownHomeWhileTheNewOneResolvesLeavesItsAnswerToMoveThePage() {
        rig.show(RecordingWebView("$HA/lovelace/1"))
        rig.admitted("lovelace")
        rig.phase = RendererPhase.CONNECTED
        rig.config.setHomeDashboard("/kitchen")
        rig.relaunch(null)
        val pendingCorrection = rig.get("provisionalHomeDashboardEpoch")
        rig.config.edit { putInt("dashboard_idle_return_min", 1) }
        rig.set("idleUnresolvedNotice", DashboardIdleReturnPolicy.UnresolvedNotice())
        rig.set("lastTouchAt", -10 * 60_000L)

        rig.call("onIdleCheck")

        assertEquals("the new home's answer still moves the page", pendingCorrection, rig.get("dashboardNavigationEpoch"))
    }
}
