package io.panelassistant.android.sensors

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveProximitySurfaceContractTest {
    // Source-text reason: the state core needs the Android service to construct.
    @Test fun homeAssistantReceivesOnlyFleetNormalizedState() {
        assertTrue(source("PanelStateCore.kt").contains("normalizedLevel?.coerceIn(0, 100)"))
        assertTrue("%" == SettingsRegistry.spec("proximity_level")!!.ha!!.facts.unit)
    }

    @Test fun wakeOnWaveHelpExplainsTheGestureAndTheTouchFallback() {
        val help = SettingsRegistry.spec("wake_on_wave")!!.help
        assertTrue(help.contains("clear-to-near-to-clear"))
        assertTrue(help.contains("touch-to-wake remains available"))
    }

    @Test fun independentReportMaskIsNotCollapsedByTheStateOwner() {
        val core = source("PanelStateCore.kt")

        assertTrue(core.contains("reportMask: Int = ProximityReportGate.BOTH"))
        assertTrue(core.contains("val admitted = admit(nextNear, level, reportMask)"))
        assertTrue(core.contains("proximityPublication.near?.let"))
        assertTrue(core.contains("proximityPublication.level?.let"))
        assertTrue(core.contains("admitted and ProximityReportGate.PRESENCE != 0"))
        assertTrue(core.contains("admitted and ProximityReportGate.LEVEL != 0"))
    }

    private fun source(relative: String): String = TestSources.kotlin(relative).readText()
}
