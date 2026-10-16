package io.panelassistant.android

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardHomeRoutingTest {
    private val own = "io.github.maxlyth.hapaneld"

    @Test fun `explicit panel launcher routes only real home intents`() {
        assertTrue(shouldRouteDashboardHomeToAdmin(own, own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME), dashboardReturn = false))
        assertFalse(shouldRouteDashboardHomeToAdmin("", own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME), dashboardReturn = false))
        assertFalse(shouldRouteDashboardHomeToAdmin("com.vendor", own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME), dashboardReturn = false))
        assertFalse(shouldRouteDashboardHomeToAdmin(own, own, null, null, dashboardReturn = false))
        assertFalse(shouldRouteDashboardHomeToAdmin(own, own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER), dashboardReturn = false))
    }

    /** With Panel admin as the launcher, the app's own HOME intent back to the dashboard shows the dashboard. */
    @Test fun `the app returning to its dashboard is not a home press`() {
        assertFalse(shouldRouteDashboardHomeToAdmin(own, own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME), dashboardReturn = true))
    }

    @Test fun `only the intent shape Android treats as HOME counts as a HOME launch`() {
        assertTrue(isHomeLaunch(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME), hasDataOrType = false))
        assertFalse(isHomeLaunch(null, null, hasDataOrType = false))
        assertFalse(isHomeLaunch(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME, Intent.CATEGORY_DEFAULT), hasDataOrType = false))
        assertFalse(isHomeLaunch(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME), hasDataOrType = true))
    }
}
