package io.panelassistant.android

import io.panelassistant.android.device.profile.BundledProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice starts on where the panel's profile declares a microphone; a stored choice always wins. */
class VoiceDefaultTest {
    private fun configOn(profileId: String, stored: Map<String, Any?> = emptyMap()) =
        Config(fakePreferences(initial = stored).instance).apply {
            attachProfile(BundledProfileFixtures.bundledById.getValue(profileId).profile())
        }

    @Test fun `a panel whose profile declares a microphone starts with voice on`() {
        listOf("nspanel-pro", "wf1589t", "tpa10", "shelly-wall-display-x2i").forEach { id ->
            assertTrue(id, configOn(id).voiceEnabled)
        }
    }

    @Test fun `a panel whose profile does not declare a microphone starts with voice off`() {
        assertFalse(configOn("generic").voiceEnabled)
        assertFalse(configOn("meta-portal").voiceEnabled)
        assertFalse(Config(fakePreferences().instance).voiceEnabled)
    }

    @Test fun `a stored choice wins over the profile default in either direction`() {
        assertFalse(configOn("nspanel-pro", mapOf("voice_enabled" to false)).voiceEnabled)
        assertTrue(configOn("generic", mapOf("voice_enabled" to true)).voiceEnabled)
    }

    @Test fun `the live store migration of a fresh store leaves the default unpersisted`() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance).apply {
            attachProfile(BundledProfileFixtures.bundledById.getValue("nspanel-pro").profile())
        }
        assertTrue(config.migrateLiveStore())
        assertFalse(prefs.values.containsKey("voice_enabled"))
        assertTrue(config.voiceEnabled)
        assertEquals("true", config.getRaw(config.specOf("voice_enabled")))
    }
}
