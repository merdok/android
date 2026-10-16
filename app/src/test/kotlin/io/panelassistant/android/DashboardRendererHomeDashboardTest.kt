package io.panelassistant.android

import io.panelassistant.android.DashboardRendererRig.Companion.HA
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
}
