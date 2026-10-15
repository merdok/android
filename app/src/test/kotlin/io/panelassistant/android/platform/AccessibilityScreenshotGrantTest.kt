package io.panelassistant.android.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScreenshotGrantTest {
    @Test fun aConnectionWithoutTheScreenshotCapabilityIsNotOffered() {
        // A firmware that masks CAPABILITY_CAN_TAKE_SCREENSHOT leaves the service live with
        // window content (1) and gestures (32) only; every capture would return null.
        assertFalse(accessibilityScreenshotGranted(30, 1 or 32))
    }

    @Test fun aConnectionHoldingTheCapabilityOnAndroid11IsOffered() {
        assertTrue(accessibilityScreenshotGranted(30, 1 or 32 or 0x80))
        assertTrue(accessibilityScreenshotGranted(34, 0x80))
    }

    @Test fun noConnectionOrAndroid10IsNeverOffered() {
        assertFalse(accessibilityScreenshotGranted(30, null))
        assertFalse(accessibilityScreenshotGranted(29, 0x80 or 1 or 32))
    }
}
