package io.panelassistant.android

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.state.StateConverger.Observation
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import org.junit.Assert.*
import org.junit.Test

internal class CommandableExposureTest : PanelRuntimeRig() {
    @Test fun obsoleteHiddenFlagsCannotFreezeCommandableScreenOrSpeakerlessVolume() {
        val rig = rig(media = null)
        try {
            rig.start()
            rig.config.setHaExposed("screen", false)
            rig.config.setHaExposed("volume", false)
            val seen = java.util.concurrent.ConcurrentHashMap<String, Observation>()
            rig.core.addStateSink { key, value -> seen[key] = value }
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", io.panelassistant.android.panelassistant.CommandValue.Light(on = false)))
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "volume", "36"))
            rig.core.publishExposureChanges(setOf("ha_expose_screen", "ha_expose_volume"))
            assertTrue("screen is still reported: $seen", seen["screen"] is Observation.Known)
            assertEquals(Observation.Known("36"), seen["volume"])
            rig.config.setHaExposed("illuminance", false)
            rig.core.publishExposureChanges(setOf("ha_expose_illuminance"))
            drainStatePump()
            assertEquals(Observation.NoReading(io.panelassistant.android.state.StateConverger.NoReadingReason.NOT_SHARED), seen["illuminance"])
        } finally { rig.close() }
    }
}
