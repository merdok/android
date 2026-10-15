package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererApplyTest {
    @Test fun darkModeReloadsTheDashboardOnlyBeforeAndroid10() {
        assertEquals(RendererApply.RELOAD, SettingsRegistry.rendererApply("dark_mode", 28))
        assertEquals(RendererApply.LIVE, SettingsRegistry.rendererApply("dark_mode", 29))
        assertTrue("dark_mode" in SettingsRegistry.rendererDeferredKeys(28))
        assertFalse("dark_mode" in SettingsRegistry.rendererDeferredKeys(29))
    }

    @Test fun aSettingTheRendererNeverReadsAndAnUnknownKeyAreNone() {
        assertEquals(RendererApply.NONE, SettingsRegistry.rendererApply("touch_sound", 34))
        assertEquals(RendererApply.NONE, SettingsRegistry.rendererApply("no_such_setting", 34))
    }

    @Test fun liveSettingsAreNeverWaitingForTheScreen() {
        val deferred = SettingsRegistry.rendererDeferredKeys(34)
        SettingsRegistry.SPECS.filter { SettingsRegistry.rendererApply(it.key, 34) == RendererApply.LIVE }
            .forEach { assertFalse(it.key, it.key in deferred) }
        assertTrue("dashboard_theme" in deferred)
        assertTrue("home_dashboard" in deferred)
    }
}
