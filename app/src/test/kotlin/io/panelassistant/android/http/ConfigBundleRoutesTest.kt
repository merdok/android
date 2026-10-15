package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.panelassistant.android.util.InstallProgress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBundleRoutesTest {
    @Test fun `fleet import previews portable settings and skips device local and secret ones`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val response = client.post("/api/v1/config/import?mode=fleet&dry_run=1") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf(
                        "touch_sound" to "false",
                        "panel_id" to "fleet_source",
                        "ha_token" to "source-panel-token",
                    )).serialize())
                }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, response.status)
                val body = JSONObject(text)
                val skipped = body.optJSONArray("skipped") ?: JSONArray()
                val skippedKeys = (0 until skipped.length()).map { skipped.getString(it) }
                val changes = body.optJSONArray("changes") ?: JSONArray()
                val changedKeys = (0 until changes.length()).map { changes.getJSONObject(it).optString("key") }
                assertTrue(text, "panel_id" in skippedKeys && "ha_token" in skippedKeys)
                assertFalse(text, "panel_id" in changedKeys || "ha_token" in changedKeys)
                assertTrue(text, "touch_sound" in changedKeys)
            }
        }
    }

    @Test fun `revision restore refused by another operation is not reported as restored`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val imported = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf("friendly_name" to "Imported panel")).serialize())
                }
                assertEquals(imported.bodyAsText(), HttpStatusCode.OK, imported.status)
                assertEquals("Imported panel", fixture.config.friendlyName)
                val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                assertEquals(1, revisions.length())
                val held = requireNotNull(InstallProgress.start("existing owner"))
                try {
                    val restored = client.post("/api/v1/config/revisions/${revisions.getJSONObject(0).getLong("id")}/restore")
                    val text = restored.bodyAsText()
                    assertEquals(text, HttpStatusCode.Conflict, restored.status)
                    assertEquals("database-compatibility-refused", JSONObject(text).optString("status"))
                    assertEquals("Imported panel", fixture.config.friendlyName)
                } finally {
                    InstallProgress.finish(held, "released")
                }
            }
        }
    }

    /** The production route owner over the fixture's real Config, with sensitive admission granted. */
    private fun Application.mountConfigBundleRoutes(fixture: PaneldServerHttpFixture) {
        val config = fixture.config
        // This fixture has no device model to name the panel from.
        config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
        val revisions = RevisionStore(fixture.directory)
        val values = ConfigValueProjection(
            config = config,
            configLiveValues = { emptyMap() },
            renderedLiveValues = { error("No page render") },
            pendingLiveSettings = { emptyMap() },
            stalledLiveSettings = { emptySet() },
            proximityJson = { error("No page render") },
            powerSafetyJson = { error("No page render") },
            haAreaCatalogJson = { error("No page render") },
        )
        val transaction = AcceptedConfigTransaction(
            config = config,
            revisions = revisions,
            rendererPreparation = RendererPreparationCoordinator(
                builtinPackage = "builtin",
                state = { RendererPreparationState("builtin", "") },
                borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
                persist = { error("No built-in renderer") },
            ),
            system = SystemController(object : SystemEnv {
                override val ownPackage = "io.github.maxlyth.hapaneld"
                override fun isInstalled(pkg: String) = false
                override fun launchComponent(pkg: String): String? = error("No launch")
                override fun homeActivities(): List<ActivityRef> = error("No HOME")
                override fun defaultHome(): ActivityRef? = error("No HOME")
                override fun directStart(component: String) = error("No launch")
            }),
            values = values,
            applySetting = { _, _ -> LiveSettingRequestOutcome.APPLIED },
            onEntityTargetChanged = {},
            setEntityLearningEnabled = { true },
            effectiveDashboardIsBuiltin = { false },
            requestTameReconcileAfterCommit = { true },
            snapInvalidate = {},
            onReconfigure = {},
        )
        routing {
            route("/api/v1") {
                configBundleRoutes {
                    ConfigBundleRoutes(
                        config = config,
                        revisions = revisions,
                        values = values,
                        transaction = transaction,
                        authorizeSensitive = { _, _, _, _ -> true },
                        rejectHardenedNetworkAdb = { _, _ -> false },
                        planEntityBackup = { error("No entity state") },
                        capabilities = { Capabilities(networkAdb = true) },
                    )
                }
            }
        }
    }
}
