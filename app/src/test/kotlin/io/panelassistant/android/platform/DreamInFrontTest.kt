package io.panelassistant.android.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamInFrontTest {
    private val own = "io.panelassistant.android"
    // A TPA10's values while com.android.dreams.basic/.Colors ran.
    private val screensavers = listOf(
        "com.android.dreams.basic/com.android.dreams.basic.Colors",
        "com.smartos.xinch.systemui/com.smartos.xinch.screen.Screensaver",
    )

    @Test fun aConfiguredScreensaverHoldingTheActiveWindowIsInFront() {
        assertTrue(dreamInFront("com.android.dreams.basic", screensavers, own))
        assertTrue("a list of components", dreamInFront("de.koshi.photodream",
            listOf("com.google.android.apps.tv.dreamx/.Ambient, de.koshi.photodream/.dream.PhotoDreamService", null), own))
    }

    @Test fun anythingElseInFrontIsNotAScreensaver() {
        assertFalse("the dashboard, also once a screensaver has ended", dreamInFront(own, screensavers + "$own/.Dream", own))
        assertFalse("another app", dreamInFront("com.google.android.youtube.tv", screensavers, own))
        assertFalse("no active window", dreamInFront(null, screensavers, own))
        assertFalse("nothing configured", dreamInFront("com.android.dreams.basic", listOf(null, null), own))
    }
}
