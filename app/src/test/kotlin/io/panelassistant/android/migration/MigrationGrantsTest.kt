package io.panelassistant.android.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationGrantsTest {
    private val own = "io.panelassistant.android"
    private val legacy = "io.github.maxlyth.hapaneld"

    private fun missing(sdk: Int, holds: Set<Pair<String, String>>) =
        AndroidSuccessorMigrationPorts.missingGrants(sdk, own, legacy) { grant, pkg -> (grant to pkg) in holds }

    @Test fun notificationsAreClaimedWhenTheSuccessorLacksThemWhateverTheLegacyAppHeld() {
        // The legacy app was denied (a person's "Don't allow", or a prompt nobody saw).
        assertEquals(setOf("NOTIFICATIONS"), missing(34, emptySet()))
        assertEquals(setOf("NOTIFICATIONS"), missing(33, setOf("NOTIFICATIONS" to legacy)))
        assertEquals(emptySet<String>(), missing(34, setOf("NOTIFICATIONS" to own)))
    }

    @Test fun belowAndroid13NotificationsAreNeverClaimed() {
        assertEquals(emptySet<String>(), missing(32, emptySet()))
        assertEquals(emptySet<String>(), missing(27, setOf("NOTIFICATIONS" to legacy)))
    }

    @Test fun otherGrantsStillFollowTheLegacyApp() {
        assertEquals(setOf("MICROPHONE"), missing(27, setOf("MICROPHONE" to legacy)))
        assertEquals(emptySet<String>(), missing(27, setOf("MICROPHONE" to legacy, "MICROPHONE" to own)))
    }

    @Test fun theLegacyAppsConnectionMustBeRegainedThroughPanelAssistant() {
        fun converged(connected: Boolean, legacyConnected: Boolean) =
            AndroidSuccessorMigrationPorts.homeAssistantConverged(connected) { legacyConnected }
        // A legacy app that reached Home Assistant holds removal until this app does.
        assertEquals(false, converged(connected = false, legacyConnected = true))
        assertEquals(true, converged(connected = true, legacyConnected = true))
        // One that never connected is not held for ever to a connection it never had.
        assertEquals(true, converged(connected = false, legacyConnected = false))
    }
}
