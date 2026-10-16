package io.panelassistant.android.state

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A diagnostic or room-climate channel reports exactly when its exposure, defaulting to the registry's, says so. */
internal class RoomClimateExposureDefaultContractTest : PanelRuntimeRig() {
    @Test fun diagnosticAndRoomClimateRuntimeUsesRegistryExposureDefaults() {
        val rig = rig(hasCht8305 = true)
        val reported = CopyOnWriteArrayList<String>()
        rig.core.addStateSink { channel, observation -> if (observation is StateConverger.Observation.Known) reported += channel }
        fun reports(key: String): Boolean {
            reported.clear()
            rig.core.reconcile(key)
            return key in reported
        }
        try {
            for (key in listOf("room_temp", "room_humidity", "diag_boot")) {
                assertEquals(key, requireNotNull(SettingsRegistry.spec(key)).haExposedByDefault, reports(key))
            }
            assertTrue("the defaults differ, or this would prove nothing", reports("room_temp") != reports("diag_boot"))

            rig.config.setHaExposed("diag_boot", true)
            rig.config.setHaExposed("room_temp", false)
            assertTrue(reports("diag_boot"))
            assertFalse(reports("room_temp"))
        } finally { rig.close() }
    }
}
