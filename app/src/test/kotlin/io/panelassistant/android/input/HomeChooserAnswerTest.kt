package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Meta Portal after an in-place update, driven through the answer the accessibility service runs:
 * HOME is unowned, the chooser shows on HOME, and a tap on its Always makes the dashboard HOME, unless
 * the app's own start-up brings the dashboard over the chooser first.
 */
class HomeChooserAnswerTest {
    private val resolver = ActivityRef("android", "com.android.internal.app.ResolverActivity")
    private val dashboard = ActivityRef("io.panelassistant.android", "io.panelassistant.android.DashboardActivity")
    private val chooser = ChooserWindow("android", "Use Panel Assistant as Home", true, 817 to 623)
    private val dashboardWindow = ChooserWindow("io.panelassistant.android", null, null, null)

    private inner class Portal {
        var home = resolver
        var front = dashboardWindow
        /** The start-up's dashboard launches arriving while a tap is on its way, one per tap. */
        var coveringStarts = 0
        /** The start-up's dashboard launches arriving before a chooser just shown is answered. */
        var coveredShows = 0
        var homePresses = 0
        val log = mutableListOf<String>()
        private var due: Long? = null

        val answer = HomeChooserAnswer(
            appName = "Panel Assistant",
            defaultHome = { home },
            activeWindow = { front },
            showChooser = {
                homePresses++
                if (home == resolver) front = chooser
                if (coveredShows > 0) { coveredShows--; front = dashboardWindow }
                true
            },
            dismiss = { front = dashboardWindow; true },
            tap = { x, y ->
                if (coveringStarts > 0) { coveringStarts--; front = dashboardWindow }
                if (front == chooser && x to y == chooser.alwaysCentre) { home = dashboard; front = dashboardWindow }
                true
            },
            answerAgainIn = { due = it },
            log = { log += it },
        )

        /** What the service runs: the answer on connect, on each chooser window, and when a recheck falls due. */
        fun settle() {
            answer.answer()
            repeat(10) {
                when {
                    front == chooser -> answer.answer()
                    due != null -> { due = null; answer.answer() }
                    else -> return
                }
            }
        }
    }

    @Test fun anUpdateLeavesTheDashboardAsHome() {
        val portal = Portal()
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(1, portal.homePresses)
    }

    @Test fun aTapTheStartUpCoveredIsAnsweredAgain() {
        // A Meta Portal, 2026-10-15: the dashboard came up 170 ms before the tap, which landed on it.
        val portal = Portal().apply { coveringStarts = 1 }
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(2, portal.homePresses)
    }

    @Test fun aChooserTheStartUpCoveredBeforeItWasAnsweredIsShownAgain() {
        // A Meta Portal, 2026-10-15: the dashboard came up 190 ms after HOME was pressed, before the answer.
        val portal = Portal().apply { coveredShows = 1 }
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(2, portal.homePresses)
    }

    @Test fun bothCoversInOneUpdateAreAnswered() {
        val portal = Portal().apply { coveredShows = 1; coveringStarts = 1 }
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(3, portal.homePresses)
    }

    @Test fun aChooserWhoseAlwaysNeverTakesIsShownAtMostThreeTimes() {
        val portal = Portal().apply { coveringStarts = Int.MAX_VALUE }
        portal.answer.connected()
        portal.settle()
        assertEquals(resolver, portal.home)
        assertEquals(3, portal.homePresses)
    }

    @Test fun aChooserOfferingAnotherAppThatThisServiceShowedIsDismissed() {
        val portal = Portal().apply { front = dashboardWindow }
        portal.answer.connected()
        portal.answer.answer()
        portal.front = chooser.copy(title = "Use Portal Launcher as Home")
        portal.answer.answer()
        assertEquals(dashboardWindow, portal.front)
        assertEquals(resolver, portal.home)
    }

    @Test fun anOwnedHomeIsLeftAlone() {
        val portal = Portal().apply { home = dashboard }
        portal.answer.connected()
        portal.settle()
        assertEquals(0, portal.homePresses)
        assertEquals(emptyList<String>(), portal.log)
    }
}
