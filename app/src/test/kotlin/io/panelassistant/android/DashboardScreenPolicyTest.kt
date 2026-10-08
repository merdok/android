package io.panelassistant.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenPolicyTest {
    @Test fun preventIdleDimOwnsTheBuiltInRendererWindowFlag() {
        assertTrue(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true))
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = false))
    }

    /** The screen-timeout screen-off cannot work while the renderer holds the screen on. */
    @Test fun aScreenTimeoutOffReleasesTheWindowFlag() {
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true, keepScreenOnReleased = true))
    }
}
