package io.panelassistant.android.state

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomClimateExposureDefaultContractTest {
    // Source-text reason: the diagnostic channels need the Android service to construct.
    private val core = File("src/main/kotlin/io/panelassistant/android/PanelStateCore.kt").readText()

    @Test fun diagnosticAndRoomClimateRuntimeUsesRegistryExposureDefaults() {
        assertEquals(
            2,
            Regex("requireNotNull\\(SettingsRegistry\\.spec\\(key\\)\\)\\.haExposedByDefault")
                .findAll(core)
                .count(),
        )
        assertTrue("config.haExposed(key, exposedByDefault)" in core)
    }
}
