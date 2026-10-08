package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `:8888` advisories as the server ships them: notice rows in a fixed order on the dashboard, and
 *  `warning_severities` aligned with `warnings` on the status endpoint. */
class NoticeRowsHttpTest {
    private val retiredGlyphs = listOf("⛔", "⚠", "ℹ", "⬆", "⟳", "🔒", "🏠", "👋")

    @Test fun `dashboard advisories render as notice rows in the established order`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            fixture.config.setDashboardZoom(125)
            fixture.server.set("storageHealth", { storage(StorageHealthSeverity.CRITICAL) })
            fixture.server.set("radioStatus", { ZigbeeHealthSnapshot(state = ZigbeeHealthState.RUNAWAY, joined = false) })
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/api/v1/info?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }
                assertEquals(HttpStatusCode.OK, response.status)
                val banners = JSONObject(response.bodyAsText()).getString("banners")
                val rows = Regex("""<div class="notice" data-severity="([a-z_]+)"[^>]*>.*?<b class="notice-title">(.*?)</b>""")
                    .findAll(banners).map { it.groupValues[1] to it.groupValues[2] }.toList()
                assertEquals(
                    listOf(
                        "action_needed" to "Storage pressure: critical",
                        "action_needed" to "Zigbee gateway is runaway",
                        "warning" to esc(formattedString(englishCatalogue, "dashboard.banner.zoom.title", "zoom" to "125")),
                    ),
                    rows,
                )
                assertFalse(banners.contains("class=\"setup"))
                retiredGlyphs.forEach { assertFalse("retired glyph $it in $banners", banners.contains(it)) }
            }
        }
    }

    @Test fun `install and configure pages draw every advisory as a glyph-free notice row`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableInstallPage(root = false)
            fixture.server.set("storageHealth", { storage(StorageHealthSeverity.WARNING) })
            fixture.server.set("radioStatus", { ZigbeeHealthSnapshot(state = ZigbeeHealthState.RUNAWAY, joined = false) })
            testApplication {
                application { fixture.server.mount(this) }
                for (page in listOf("/install?lang=en", "/configure?lang=en")) {
                    val html = client.get(page) { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                    assertFalse(page, html.contains("class=\"setup"))
                    assertTrue(page, html.contains("""<div class="notice" data-severity="update" id="verbar" style="display:none">"""))
                    assertTrue(page, html.contains("""<div id="halifebar" class="notice" style="display:none"></div>"""))
                    assertTrue(page, html.contains("""<div id="hanetbar" class="notice" style="display:none"></div>"""))
                    assertTrue(page, html.contains("""data-severity="warning" id="migrationbar""""))
                    val rows = Regex("""<div class="notice"[^>]*>.*?</div>""").findAll(html).map { it.value }.toList()
                    assertTrue(page, rows.size >= 3)
                    rows.forEach { row -> retiredGlyphs.forEach { assertFalse("$page: $it in $row", row.contains(it)) } }
                }
                val install = client.get("/install?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue(install.contains("""data-severity="action_needed"><span class="notice-pill">Action needed</span><span class="notice-text"><b class="notice-title">Zigbee gateway is runaway</b>"""))
            }
        }
    }

    @Test fun `status warning severities align one to one with warnings`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useManagementStatus {}
            fixture.values["zigbee_router_configured"] = true
            fixture.values["zigbee_router_enabled"] = true
            fixture.server.set("storageHealth", { storage(StorageHealthSeverity.CRITICAL) })
            fixture.server.set("radioStatus", { ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED, joined = false) })
            testApplication {
                application { fixture.mount(this) }
                val json = JSONObject(client.get("/api/v1/status").bodyAsText())
                val warnings = json.getJSONArray("warnings")
                val severities = json.getJSONArray("warning_severities")
                assertEquals(warnings.length(), severities.length())
                val pairs = (0 until warnings.length()).associate { warnings.getString(it) to severities.getString(it) }
                assertEquals("action_needed", pairs.entries.single { it.key.startsWith("Storage pressure: critical — ") }.value)
                assertEquals("warning", pairs.entries.single { it.key.startsWith("Zigbee router is enabled but not joined — ") }.value)
                pairs.keys.forEach { warning -> retiredGlyphs.forEach { assertFalse(warning, warning.startsWith(it)) } }
            }
        }
    }

    @Test fun `status without warnings still carries an empty severity list`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useManagementStatus {}
            testApplication {
                application { fixture.mount(this) }
                val json = JSONObject(client.get("/api/v1/status").bodyAsText())
                assertTrue(json.has("warning_severities"))
                assertEquals(json.getJSONArray("warnings").length(), json.getJSONArray("warning_severities").length())
            }
        }
    }

    private fun PaneldServer.set(name: String, value: Any) {
        PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun storage(severity: StorageHealthSeverity) = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = severity,
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 30L * 1024 * 1024,
        totalBytes = 4L * 1024 * 1024 * 1024,
        usedPercent = 99.3,
        mainDatabaseBytes = 20L * 1024 * 1024,
        walBytes = 3L * 1024 * 1024,
        sidecarBytes = 64L * 1024,
        pageSizeBytes = 4_096L,
        pageCount = 1_234L,
        freelistCount = 45L,
        schemaVersion = 11,
        quickCheck = StorageQuickCheck.OK,
        databaseFailureKind = null,
        databaseFailureOperation = null,
    )
}
