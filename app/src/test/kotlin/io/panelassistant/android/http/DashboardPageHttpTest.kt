package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPageHttpTest {
    @Test fun `warm dashboard renders escaped facts private values and live diagnostic rows`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            fixture.config.setDashboardZoom(125)
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/?lang=en") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val html = response.bodyAsText()
                assertTrue(html.contains("data-hydrate=\"0\""))
                assertTrue(html.contains("Warm &lt;panel&gt;"))
                assertTrue(html.contains("<span class=\"secret\">secret-value</span>"))
                assertTrue(html.contains("id=\"halifecell\""))
                assertTrue(html.contains("id=\"hanetcell\""))
                assertTrue(html.contains("50% (128)"))
                assertTrue(html.contains("name=\"dashboard_zoom\" value=\"100\""))
                assertTrue(html.contains("action=\"api/v1/config\""))
                val hydration = client.get("/api/v1/info?lang=en")
                assertEquals(HttpStatusCode.OK, hydration.status)
                val payload = org.json.JSONObject(hydration.bodyAsText())
                val cards = payload.getJSONObject("cards")
                assertTrue(cards.getString("infotbl").contains("Warm &lt;panel&gt;"))
                assertTrue(cards.getString("infotbl").contains("<span class=\"secret\">secret-value</span>"))
                assertTrue(cards.getString("contexttbl").contains("id=\"halifecell\""))
                assertTrue(cards.getString("livetbl").contains("50% (128)"))
                assertTrue(payload.getString("banners").contains("name=\"dashboard_zoom\" value=\"100\""))
            }
        }
    }

    @Test fun `cold dashboard renders hydration shell without management probes`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableColdDashboard()
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/?lang=en") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val html = response.bodyAsText()
                assertTrue(html.contains("data-hydrate=\"cold\""))
                assertTrue(html.contains("data-capture-ok=\"0\""))
                assertTrue(html.contains("id=\"infotbl\""))
                assertTrue(html.contains("id=\"contexttbl\""))
                assertTrue(html.contains("Contract &lt;panel&gt;"))
                val refused = client.get("/") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                    header(HttpHeaders.Host, "elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
            }
        }
    }

    @Test fun `a panel without root offers the screenshot only where the accessibility capture serves`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            testApplication {
                application { fixture.server.mount(this) }
                suspend fun shot() = org.json.JSONObject(client.get("/api/v1/info?lang=en").bodyAsText()).getBoolean("shot")
                assertEquals(false, shot())
                val without = client.get("/?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue(without.contains("data-capture-ok=\"0\" style=\"display:none\""))
                fixture.useInteractive(io.panelassistant.android.control.InteractiveController(
                    canSu = false,
                    root = io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
                    daemon = io.panelassistant.android.control.FakeDaemon(available = false),
                    accessibility = object : io.panelassistant.android.platform.AccessibilityActions {
                        override fun back() = false
                        override fun recents() = false
                        override fun tap(x: Int, y: Int) = false
                        override fun canScreenshot() = true
                    },
                ))
                assertEquals(true, shot())
                val html = client.get("/?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue(html.contains("id=\"shotcard\" data-layout-key=\"screenshot\" data-capture-ok=\"1\""))
            }
        }
    }
}
