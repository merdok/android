package io.panelassistant.android.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry side of the illuminance gate. Whether the panel describes the channel, and the live
 * change when that answer changes, are pinned through the production state core in
 * [io.panelassistant.android.state.NativeLightChannelTest].
 */
class IlluminanceAvailabilityContractTest {
    @Test fun `an unavailable light sensor withdraws the illuminance entity`() {
        val spec = SettingsRegistry.spec("illuminance")!!
        assertFalse(spec.availableWhen(Capabilities(hasLight = false)))
        assertTrue(spec.availableWhen(Capabilities(hasLight = true)))
    }
}
