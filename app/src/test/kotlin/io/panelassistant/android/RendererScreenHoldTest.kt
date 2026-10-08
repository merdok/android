package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeBacklight
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeScreenPower
import io.panelassistant.android.control.FakeWakeTap
import io.panelassistant.android.control.InMemoryDisplacedScreenTimeout
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.device.ScreenOff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-timeout off as the dashboard sees it: [ScreenController] releases the renderer's hold
 * through [BuiltinDashboard], and [RendererScreenHold] — bound exactly as [DashboardActivity] binds it,
 * with a fake window and view tree in place of Android's — must leave nothing keeping the screen on
 * while Android counts down, and give it all back on wake.
 */
class RendererScreenHoldTest {
    /** A view: a node that may hold keep-screen-on, as Chromium's media anchor view inside the WebView does. */
    private class Node(var keepScreenOn: Boolean = false, val children: MutableList<Node> = mutableListOf())

    private val mediaAnchor = Node(keepScreenOn = true)
    private val webView = Node(children = mutableListOf(mediaAnchor))
    private val decor = Node(children = mutableListOf(Node(), webView))
    private var windowKeepScreenOn = false

    private val hold = RendererScreenHold<Node>(
        preventIdleDim = { true },
        setWindowKeepScreenOn = { windowKeepScreenOn = it },
        root = { decor },
        children = { it.children },
        viewKeepsScreenOn = { it.keepScreenOn },
        setViewKeepScreenOn = { node, keep -> node.keepScreenOn = keep },
    )

    private val power = FakeScreenPower().apply {
        canWrite = true
        timeoutMs = 300_000
        shortTimeoutSleeps = false    // Android counts down; the test decides when it goes to sleep
    }

    private fun controller(nap: (Long) -> Unit) = ScreenController(
        FakeBacklight(200), power, FakeRootShell(runResult = false), FakeDaemon(), FakeWakeTap(),
        ScreenOff.SCREEN_TIMEOUT, nap = nap, displacedTimeout = InMemoryDisplacedScreenTimeout(),
        touchCount = { 0L },
    )

    @After fun tearDown() {
        hold.detach()
        BuiltinDashboard.releaseKeepScreenOn(false)
    }

    @Test fun nothingHoldsTheScreenOnWhileTheShortTimeoutRunsAndEverythingComesBackOnWake() {
        hold.attach { it() }         // the activity posts to its UI thread; here that is this thread
        hold.apply()                 // the activity's first policy pass
        assertTrue(windowKeepScreenOn)

        val duringWait = mutableListOf<Pair<Boolean, Boolean>>()
        var naps = 0
        val sc = controller(nap = {
            duringWait += windowKeepScreenOn to mediaAnchor.keepScreenOn
            if (++naps == 5) power.interactive = false     // Android's own timeout fires
        })

        sc.sleep()
        assertEquals(ScreenOff.SCREEN_TIMEOUT, sc.routeSelection().selected)
        assertTrue("the wait was observed", duringWait.isNotEmpty())
        assertTrue("window flag clear for the whole wait: $duringWait", duringWait.none { it.first })
        assertTrue("WebView's own hold clear for the whole wait: $duringWait", duringWait.none { it.second })

        sc.wake()
        assertTrue("prevent-idle-dim holds the screen on again", windowKeepScreenOn)
        assertTrue("the WebView's hold is given back", mediaAnchor.keepScreenOn)
    }

    /** The WebView can take its hold again mid-wait (a new media element starts); the next tick drops it. */
    @Test fun aHoldTakenDuringTheWaitIsDroppedAgain() {
        hold.attach { it() }
        hold.apply()
        val lateAnchor = Node()
        var naps = 0
        var heldAfterRetake = false
        val sc = controller(nap = {
            naps++
            if (naps == 2) { lateAnchor.keepScreenOn = true; webView.children += lateAnchor }
            if (naps == 4) heldAfterRetake = lateAnchor.keepScreenOn
            if (naps == 6) power.interactive = false
        })

        sc.sleep()
        assertFalse("the re-taken hold was dropped by the next tick", heldAfterRetake)
        sc.wake()
        assertTrue(lateAnchor.keepScreenOn)
    }

    /** A hold the WebView let go of (its view removed) while asleep is not resurrected on wake. */
    @Test fun aHoldWhoseViewLeftTheTreeIsNotGivenBack() {
        hold.attach { it() }
        hold.apply()
        val sc = controller(nap = { power.interactive = false })
        sc.sleep()
        webView.children.remove(mediaAnchor)

        sc.wake()
        assertFalse(mediaAnchor.keepScreenOn)
        assertTrue(windowKeepScreenOn)
    }
}
