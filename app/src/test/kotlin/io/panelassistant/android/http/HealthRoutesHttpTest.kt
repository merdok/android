package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRoutesHttpTest {
    @Test fun `root and v1 health retain tokens and real root admission`() {
        PaneldServerHttpFixture().use { fixture ->
            initializeHealth(fixture)
            testApplication {
                application { fixture.mount(this) }
                val direct = createClient { followRedirects = false }
                val root = direct.get("/health")
                val api = direct.get("/api/v1/health")
                for (response in listOf(root, api)) {
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                    assertEquals("DENY", response.headers["X-Frame-Options"])
                    assertEquals(null, response.headers[HttpHeaders.Location])
                }
                val line = root.bodyAsText()
                assertEquals(line, api.bodyAsText())
                assertTrue(line, line.startsWith("ha-paneld ${Config.VERSION} panel=contract-panel build=${Config.VERSION} cfg="))
                assertTrue(line, Regex(" cfg=[0-9a-f]{8} ").containsMatchIn(line))
                assertTrue(line, line.contains(" pkg=io.github.maxlyth.hapaneld vc=${BuildConfig.VERSION_CODE} proto=5-5 "))
                assertTrue(line, line.endsWith(" pa_restarting=app,settings,29000 pa_notice=0\n"))
                val refused = direct.get("/health") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }

    @Test fun `only a broker-era upgrade that never met Panel Assistant reports the migration notice`() {
        fun notice(stored: Map<String, Any?>): List<String> = PaneldServerHttpFixture().use { fixture ->
            fixture.values.putAll(stored)
            initializeHealth(fixture)
            var lines = emptyList<String>()
            testApplication {
                application { fixture.mount(this) }
                // Two startups: the second must not turn a fresh store into an upgraded one.
                lines = List(2) {
                    assertTrue(fixture.config.migrateLiveStore())
                    client.get("/health").bodyAsText().substringAfter(" pa_notice=").trim()
                }
            }
            lines
        }
        assertEquals("fresh install waiting for setup", listOf("0", "0"), notice(emptyMap()))
        assertEquals("0.9 upgrade never connected", listOf("1", "1"), notice(BROKER_ERA_STORE))
        assertEquals(
            "0.9 upgrade Panel Assistant already managed",
            listOf("0", "0"),
            notice(BROKER_ERA_STORE + ("panel_assistant_authority" to "native")),
        )
    }

    @Test fun `an upgraded panel's notice clears on its first Panel Assistant session`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.values.putAll(BROKER_ERA_STORE)
            initializeHealth(fixture)
            assertTrue(fixture.config.migrateLiveStore())
            testApplication {
                application { fixture.mount(this) }
                assertTrue(client.get("/health").bodyAsText().endsWith(" pa_notice=1\n"))
                fixture.config.markPanelAssistantConnected()
                assertTrue(client.get("/health").bodyAsText().endsWith(" pa_notice=0\n"))
            }
        }
    }

    @Test fun `migration dismissal is origin guarded durable and reflected on both health routes`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.values.putAll(BROKER_ERA_STORE)
            initializeHealth(fixture)
            assertTrue(fixture.config.migrateLiveStore())
            testApplication {
                application { fixture.mount(this) }
                val refused = client.post("/api/v1/migration-notice/dismiss") {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertTrue(fixture.config.migrationNoticeVisible())
                repeat(2) {
                    val dismissed = client.post("/api/v1/migration-notice/dismiss")
                    assertEquals(HttpStatusCode.OK, dismissed.status)
                    assertEquals("application/json", dismissed.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    assertEquals("{\"ok\":true}", dismissed.bodyAsText())
                    assertFalse(fixture.config.migrationNoticeVisible())
                }
                for (path in listOf("/health", "/api/v1/health")) {
                    assertTrue(client.get(path).bodyAsText().endsWith(" pa_notice=0\n"))
                }
            }
        }
    }

    private companion object {
        /** What a 0.9.11 panel that ran the broker client leaves in its store. */
        val BROKER_ERA_STORE = mapOf<String, Any?>(
            "config_schema" to 14,
            "mqtt_broker" to "tcp://ha.local:1883",
            "last_discovery_version" to "0.9.11",
        )
    }

    private fun initializeHealth(fixture: PaneldServerHttpFixture) {
        fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
        // Fill the existing fixture's runtime collaborators, not an alternate route or admission path.
        PaneldServer::class.java.getDeclaredField("configLiveValues").apply {
            isAccessible = true
        }.set(fixture.server, { emptyMap<String, String>() })
        PaneldServer::class.java.getDeclaredField("panelAssistantTransport").apply {
            isAccessible = true
        }.set(fixture.server, TestTransportOwners.idle().apply { announceRestart("app", "settings", 29_000L) })
    }
}
