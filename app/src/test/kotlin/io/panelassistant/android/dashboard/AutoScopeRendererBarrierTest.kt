package io.panelassistant.android.dashboard

import io.panelassistant.android.Config
import io.panelassistant.android.DashboardEntityDefaultResolverMigration
import io.panelassistant.android.clearDashboardEntityDefaultResolverRebootstrapPending
import io.panelassistant.android.commitDashboardEntitySubscription
import io.panelassistant.android.dashboardEntityFilterEnabled
import io.panelassistant.android.dashboardEntityTargetKey
import io.panelassistant.android.fakePreferences
import io.panelassistant.android.migrateDashboardEntityDefaultResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The exact case the review named: `Auto` selected, a previous sync on record, an enabled allow-list
 * retained for `/alpha`, and the account default now resolving to `/beta`. Scheduling a scan fixes
 * where the panel ends up; this fixes what it may install before the scan answers.
 */
class AutoScopeRendererBarrierTest {

    private val auto = ""

    @Before fun reset() = AutoScopeVerification.invalidate()

    @After fun clear() = AutoScopeVerification.invalidate()

    @Test fun `an office allow-list cannot reach document start while Auto is unverified`() {
        assertTrue(
            "the retained list belongs to a dashboard nobody has vouched for this process",
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true,
                filterEnabled = true,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    @Test fun `the barrier lifts only once a live resolution has answered`() {
        AutoScopeVerification.markVerified(auto)
        assertFalse(
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true,
                filterEnabled = true,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    @Test fun `a delayed or failed resolution leaves the renderer held`() {
        // Nothing marks verification on a resolution that never returns or throws, so the absence of a
        // positive signal is what keeps the hold — not a timeout anyone has to remember to arm.
        assertTrue(AutoScopeVerification.unverified(auto))
        assertTrue(
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true, filterEnabled = true,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    @Test fun `verification is scoped to the setting it answered`() {
        // Answering for Auto must not vouch for a later Auto whose account default has since moved,
        // and invalidation is what re-opens it.
        AutoScopeVerification.markVerified(auto)
        AutoScopeVerification.invalidate()
        assertTrue(AutoScopeVerification.unverified(auto))
    }

    @Test fun `an explicitly configured dashboard is never held for verification`() {
        // Its scope is configured rather than discovered, so a live read could add nothing and holding
        // would strand every explicit panel behind a round trip it does not need.
        for (explicit in listOf("/lovelace", "/alpha/beta")) {
            assertFalse(explicit, AutoScopeVerification.unverified(explicit))
            assertFalse(
                explicit,
                shouldHoldRendererForEntityBootstrap(
                    learningEnabled = true, filterEnabled = true,
                    autoScopeUnverified = AutoScopeVerification.unverified(explicit),
                ),
            )
        }
    }

    @Test fun `status and the entity list hold exactly while the renderer holds`() {
        // The renderer, the manager's status and entity query, and the Configure notice all ask through
        // the Config form, so this is the one answer in every verification state, including after a pin
        // or an ignore re-opens verification.
        val autoPanel = learnedAutoPanel()
        val explicitPanel = learnedExplicitPanel()
        assertTrue("fixture precondition", autoPanel.dashboardEntityFilterEnabled)
        assertTrue("fixture precondition", explicitPanel.dashboardEntityFilterEnabled)

        assertTrue(shouldHoldRendererForEntityBootstrap(autoPanel))
        assertFalse(shouldHoldRendererForEntityBootstrap(explicitPanel))

        AutoScopeVerification.markVerified(auto)
        assertFalse(shouldHoldRendererForEntityBootstrap(autoPanel))

        AutoScopeVerification.invalidate()
        assertTrue(shouldHoldRendererForEntityBootstrap(autoPanel))
        assertFalse(shouldHoldRendererForEntityBootstrap(explicitPanel))
    }

    @Test fun `an absent allow-list still holds regardless of verification`() {
        AutoScopeVerification.markVerified(auto)
        assertTrue(
            "the pre-existing invariant must survive this change",
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true, filterEnabled = false,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    private fun learnedAutoPanel(): Config {
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to auto,
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/",
            "dashboard_entity_learning" to true,
        ))
        return Config(prefs.instance).apply {
            assertEquals(DashboardEntityDefaultResolverMigration.REBOOTSTRAP, migrateDashboardEntityDefaultResolver())
            assertTrue(commitDashboardEntitySubscription(true, listOf("light.office"), applied = true))
            assertTrue(clearDashboardEntityDefaultResolverRebootstrapPending())
        }
    }

    private fun learnedExplicitPanel(): Config {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        return Config(fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to "/lovelace/kiosk",
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/lovelace",
            "dashboard_entity_filter_instance" to target,
            "dashboard_entity_applied_instance" to target,
            "dashboard_entity_learning" to true,
            "dashboard_entity_learning_applied" to true,
            "dashboard_entity_filter_enabled" to true,
            "dashboard_entity_filter_ids" to "light.kiosk",
        )).instance)
    }
}
