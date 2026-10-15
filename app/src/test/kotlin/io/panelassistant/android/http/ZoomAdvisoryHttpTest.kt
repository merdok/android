package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.DensityController
import io.panelassistant.android.control.FakeAppSizing
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.SystemController
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dashboard-zoom advisory nudges toward density only where a density route can set density. */
class ZoomAdvisoryHttpTest {
    private val zoomNotice = "name=\"dashboard_zoom\" value=\"100\""

    private fun pageHtml(densityCanBeSet: Boolean, zoom: Int, path: String = "/install?lang=en"): String {
        val density = DensityController(
            false,
            FakeRootShell(available = false, runResult = false),
            FakeDaemon(available = false),
            FakeAppSizing(base = 160, granted = densityCanBeSet),
        )
        lateinit var html: String
        PaneldServerHttpFixture(density = density).use { fixture ->
            fixture.enablePages()
            if (path.startsWith("/install")) fixture.enableInstallPage(root = false) else fixture.enableWarmDashboard()
            fixture.config.setDashboardPackage(SystemController.BUILTIN_DASHBOARD)
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("dashboard_zoom")), zoom.toString())
            // The snapshot's sizing as production observes it: through the controller, no su, no helper.
            val privilege = PrivilegedRouteObservation(directSuReady = false, helperRootReady = false)
            val sizing = density.observeSizing(privilege)
            fixture.snapCache.set(ManagementSnapshot(
                emptyMap(), emptyMap(), io.panelassistant.android.config.Capabilities(), emptyList(),
                privilege, sizing.current, sizing.base, sizing.fontScale, false,
            ))
            testApplication {
                application { fixture.server.mount(this) }
                html = client.get(path) { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
            }
        }
        return html
    }

    @Test fun `a rootless panel whose density cannot be set keeps its profile zoom without a density nudge`() {
        // meta-portal-10-gen2: density 160 kept, dashboard_zoom 85 is the sizing tool.
        assertEquals(false, pageHtml(densityCanBeSet = false, zoom = 85).contains(zoomNotice))
    }

    @Test fun `a panel whose density can be set still hears about an off-100 zoom`() {
        assertEquals(true, pageHtml(densityCanBeSet = true, zoom = 125).contains(zoomNotice))
    }

    @Test fun `the dashboard banner stays quiet about zoom on a rootless panel whose density cannot be set`() {
        assertEquals(false, pageHtml(densityCanBeSet = false, zoom = 85, path = "/?lang=en").contains(zoomNotice))
    }
}
