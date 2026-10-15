package io.panelassistant.android

import io.panelassistant.android.DashboardRendererRig.Companion.HA
import io.panelassistant.android.control.BuiltinDashboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the renderer's phase decides at its own entry points: the memory-ceiling check, the handshake
 * watchdog fire and the teardown every rebuild runs. Each case is a defect the three flags the phase
 * replaced allowed; see [DashboardRendererRig] for how the activity runs on the JVM.
 */
class DashboardRendererPhaseTest {
    private val rig = DashboardRendererRig()

    @After fun release() = rig.close()

    /**
     * A connected page froze at screen-off; hours later the memory ceiling reloads it behind the dark
     * screen. A WebView with its timers paused cannot load, so the reload runs live through the settle
     * window, as every other dark load does, instead of leaving the panel to wake onto a page that only
     * then starts loading.
     */
    @Test fun aDarkMemoryReloadOfAFrozenDashboardRunsLive() {
        val page = RecordingWebView("$HA/lovelace/0")
        rig.show(page)
        rig.phase = RendererPhase.CONNECTED
        rig.set("screenAwake", false)
        // Past the six-hour ceiling: elapsedRealtime reads 0 on the JVM.
        rig.set("lastFullLoadAt", -(6 * 60 * 60 * 1000L + 1))

        rig.call("onPeriodicCheck")

        assertEquals("the reload, then the page let run", listOf("reload", "onResume", "resumeTimers"), page.calls)
    }

    /**
     * The on-panel sign-in has no handshake to wait for. A watchdog fire over it used to reload it,
     * discarding whatever the person had typed, and then again on every backoff.
     */
    @Test fun aWatchdogFireLeavesTheOnPanelSignInAlone() {
        val signIn = RecordingWebView("http://127.0.0.1:8888/ha-oauth/start")
        rig.set("signInShownForUrl", HA)
        rig.phase = RendererPhase.SIGN_IN
        rig.show(signIn, committed = null)

        rig.call("onWatchdogTimeout")

        assertEquals("nothing reached the sign-in page", emptyList<String>(), signIn.calls)
    }

    /**
     * Every rebuild tears the WebView down first (a route change, a re-admission for the home dashboard, an
     * admission retry, the sign-in, a status screen). One that ran under the reconnecting cover kept the
     * reconnecting state, so the rebuilt page's first network error could not raise the cover again and
     * Chromium's own error page stayed on the panel until the next watchdog retry.
     */
    @Test fun aTeardownUnderTheReconnectingCoverEndsIt() {
        rig.show(RecordingWebView("data:text/html,"))
        rig.phase = RendererPhase.RECONNECTING
        rig.coverShown()

        rig.call("teardownWeb")

        assertEquals(
            "after the teardown: [still reconnecting, old cover still standing]",
            listOf(false, false),
            listOf(rig.phase == RendererPhase.RECONNECTING, rig.coverUp),
        )
    }

    /**
     * The settings pages read which settings the dashboard does not show yet. A value the connected load
     * carried leaves the report; one the WebView was never built with stays in it.
     */
    @Test fun aConnectedLoadTakesWhatItCarriedOutOfThePendingReport() {
        val page = RecordingWebView("$HA/lovelace")
        rig.show(page)
        rig.phase = RendererPhase.LOADING
        rig.call("onLoadStarted", 25_000L)
        val session = rig.bridge.beginDocument(page, rig.config, rig.get("rendererGeneration") as Long)

        rig.call("onConnectionStatus", ExternalBusProtocol.ConnectionEvent.CONNECTED, rig.get("rendererGeneration"), session)

        val pending = requireNotNull(BuiltinDashboard.pendingRendererSettings()) { "the renderer publishes a report" }
        assertTrue("the zoom the load carried is on screen: $pending", "dashboard_zoom" !in pending)
        assertTrue("the theme this WebView was not built with is not: $pending", "dashboard_theme" in pending)
    }

    /** A refused login belongs to the credential, not to the WebView: a rebuild keeps it until consent. */
    @Test fun aTeardownKeepsARefusedLogin() {
        rig.show(RecordingWebView("data:text/html,"))
        rig.phase = RendererPhase.AUTH_LATCHED
        BuiltinDashboard.setActivityAuthLatched(rig.owner, true)

        rig.call("teardownWeb")

        assertEquals(RendererPhase.AUTH_LATCHED, rig.phase)
        assertTrue("the health warning still reports the refused login", BuiltinDashboard.authLatched)
    }
}
