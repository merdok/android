package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test fun theAppIsItsOwnIdOrTheIdItWasPublishedUnderBefore() {
        assertEquals("io.panelassistant.android", AppIdentity.OWN)
        assertTrue(AppIdentity.isPanelApp(AppIdentity.OWN))
        assertTrue(AppIdentity.isPanelApp("io.github.maxlyth.hapaneld"))
        assertFalse(AppIdentity.isPanelApp("io.panelassistant.android.debug"))
        assertFalse(AppIdentity.isPanelApp(null))
    }

    @Test fun ownComponentsUseTheShorthandBecauseTheIdIsTheKotlinPackage() {
        assertEquals(
            "io.panelassistant.android/.input.PanelAccessibilityService",
            AppIdentity.component(AppIdentity.OWN, ".input.PanelAccessibilityService"),
        )
    }

    @Test fun anotherIdNamesTheClassInTheKotlinPackage() {
        assertEquals(
            "io.github.maxlyth.hapaneld/io.panelassistant.android.DashboardActivity",
            AppIdentity.component(AppIdentity.LEGACY, ".DashboardActivity"),
        )
    }

    @Test fun aClassNameThatIsNotRelativeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            AppIdentity.component(AppIdentity.OWN, "DashboardActivity")
        }
    }
}
