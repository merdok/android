package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a Meta Portal showed after an in-place update: HOME unowned, Always preselected. */
class HomeChooserPolicyTest {
    private val resolver = ActivityRef("android", "com.android.internal.app.ResolverActivity")
    private val dashboard = ActivityRef("io.panelassistant.android", "io.panelassistant.android.DashboardActivity")

    private fun step(
        home: ActivityRef? = resolver,
        window: String? = "android",
        title: String? = "Use Panel Assistant as Home",
        enabled: Boolean? = true,
        shown: Boolean = false,
    ) = HomeChooserPolicy.next(home, window, title, enabled, "Panel Assistant", shown)

    @Test fun tapsAlwaysWhenAndroidsChooserOffersThisApp() {
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step())
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step(title = "Panel Assistant als Startbildschirm verwenden"))
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step(shown = true))
    }

    @Test fun leavesHomeAloneOnceItIsOwned() {
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard, window = "io.panelassistant.android"))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = ActivityRef("com.facebook.alohaapps.launcher", "HomeActivity")))
    }

    @Test fun showsTheChooserOnceWhenTheDashboardCoversIt() {
        // After an update the app's start-up brings the dashboard over the chooser before it is answered.
        assertEquals(HomeChooserPolicy.Step.SHOW_CHOOSER, step(window = "io.panelassistant.android"))
        assertEquals(HomeChooserPolicy.Step.SHOW_CHOOSER, step(window = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(window = "io.panelassistant.android", shown = true))
    }

    @Test fun neverTapsForAnotherAppAndDismissesOnlyAChooserItShowed() {
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = "Use Portal Launcher as Home"))
        assertEquals(HomeChooserPolicy.Step.DISMISS, step(title = "Use Portal Launcher as Home", shown = true))
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(enabled = false))
        assertEquals(HomeChooserPolicy.Step.DISMISS, step(enabled = false, shown = true))
    }

    @Test fun anotherSystemWindowIsNotTheChooser() {
        // An Android dialog with no Always button, such as "app isn't responding".
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = null, enabled = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = null, enabled = null, shown = true))
    }

    private val pkg = "io.panelassistant.android"
    private val service = "$pkg.input.PanelAccessibilityService"
    private val portal = "$pkg/.input.PanelAccessibilityService:" +
        "com.facebook.aloha.system.device/.accessibility.KeyEventAccessibilityService"

    @Test fun aConnectionWithoutWindowContentReEnablesOnceKeepingOtherServices() {
        // A Meta Portal after the update that added window content: capabilities 40, not 41.
        assertEquals(
            "com.facebook.aloha.system.device/.accessibility.KeyEventAccessibilityService",
            HomeChooserPolicy.rebindEntries(portal, pkg, service, 40),
        )
        assertEquals("", HomeChooserPolicy.rebindEntries("$pkg/$service", pkg, service, 40))
        // The old app id's leftover entry is another package's service and stays.
        val legacy = "io.github.maxlyth.hapaneld/.input.PanelAccessibilityService"
        assertEquals(legacy, HomeChooserPolicy.rebindEntries("$legacy:$pkg/$service", pkg, service, 40))
    }

    @Test fun noRebindWhenTheCapabilityIsAlreadyThereOrTheServiceIsNotListed() {
        assertNull(HomeChooserPolicy.rebindEntries(portal, pkg, service, 41))
        assertNull(HomeChooserPolicy.rebindEntries("com.other/.Service", pkg, service, 40))
        assertNull(HomeChooserPolicy.rebindEntries(null, pkg, service, 40))
    }
}
