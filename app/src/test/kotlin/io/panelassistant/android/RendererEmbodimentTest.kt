package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contract behind the settings pages' "on screen yet": a value leaves the pending set only once a
 * load carrying it has connected, the moment the restart card drops.
 */
class RendererEmbodimentTest {
    private val embodiment = RendererEmbodiment()

    @Test fun aChangedValueIsPendingUntilARebuiltPageCarryingItConnects() {
        embodiment.built(mapOf("dashboard_theme" to "dark"))
        embodiment.connected()
        assertEquals(emptySet<String>(), embodiment.pending(mapOf("dashboard_theme" to "dark")))

        val wanted = mapOf("dashboard_theme" to "light")
        assertEquals(setOf("dashboard_theme"), embodiment.pending(wanted))
        embodiment.built(wanted)
        assertEquals("built but not yet connected", setOf("dashboard_theme"), embodiment.pending(wanted))
        embodiment.connected()
        assertEquals(emptySet<String>(), embodiment.pending(wanted))
    }

    @Test fun aLoadThatNeverConnectsLeavesItsValuePending() {
        embodiment.built(mapOf("dashboard_zoom" to "100"))
        embodiment.connected()
        embodiment.loadStarted(mapOf("dashboard_zoom" to "120"))

        assertEquals(setOf("dashboard_zoom"), embodiment.pending(mapOf("dashboard_zoom" to "120")))
    }

    @Test fun aReloadCarriesOnlyThePageLoadValuesAndKeepsWhatTheWebViewWasBuiltWith() {
        embodiment.built(mapOf("dashboard_theme" to "dark", "dashboard_zoom" to "100"))
        embodiment.loadStarted(mapOf("dashboard_zoom" to "120"))
        embodiment.connected()

        assertEquals(
            "a reload does not re-bake the theme script",
            setOf("dashboard_theme"),
            embodiment.pending(mapOf("dashboard_theme" to "light", "dashboard_zoom" to "120")),
        )
    }

    @Test fun aChangeTheRendererFoundNothingToRebuildForIsShownWhenThePageIsConnected() {
        embodiment.built(mapOf("dashboard_theme" to "follow"))
        embodiment.connected()
        val wanted = mapOf("dashboard_theme" to "ambient")

        embodiment.reconciled(wanted, connected = true)

        assertEquals(emptySet<String>(), embodiment.pending(wanted))
    }

    @Test fun aChangeTheRendererFoundNothingToRebuildForWaitsForTheLoadInFlight() {
        embodiment.built(mapOf("dashboard_theme" to "follow"))
        val wanted = mapOf("dashboard_theme" to "ambient")

        embodiment.reconciled(wanted, connected = false)
        assertEquals(setOf("dashboard_theme"), embodiment.pending(wanted))
        embodiment.connected()
        assertEquals(emptySet<String>(), embodiment.pending(wanted))
    }
}
