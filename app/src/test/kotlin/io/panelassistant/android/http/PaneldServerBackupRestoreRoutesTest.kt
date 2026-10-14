package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileActivationState
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileBackup
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileStatus
import io.panelassistant.android.config.SettingsRegistry
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.panelassistant.android.util.InstallProgress
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServerBackupRestoreRoutesTest {
    @Test fun `full mount requires an explicit backup request before capture`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for ((form, error) in listOf(
                    "include_companion=invalid&allow_plaintext=1" to "invalid-include-companion",
                    "include_companion=0" to "passphrase-required",
                )) {
                    val response = client.post("/api/v1/backup") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody(form)
                    }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals(error, JSONObject(response.bodyAsText()).getString("error"))
                }
            }
        }
    }

    @Test fun `full mount refuses restore while another operation owns admission`() {
        val held = requireNotNull(InstallProgress.start("existing owner"))
        try {
            PaneldServerHttpFixture().use { fixture ->
                testApplication {
                    application { fixture.mount(this) }
                    val response = client.post("/api/v1/restore?dry_run=1") { setBody("not a backup") }
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertEquals("busy", JSONObject(response.bodyAsText()).getString("status"))
                }
            }
        } finally {
            InstallProgress.finish(held, "released")
        }
    }

    @Test fun `full mount previews legacy config without changing the panel`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":1,"panel_id":"source","config":{"panel_id":"restored"}}""")
                }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                val body = JSONObject(response.bodyAsText())
                assertEquals(true, body.getBoolean("dry_run"))
                // Migrated voice enablement is unavailable on this fixture without a microphone.
                assertEquals(26, body.getInt("config_keys"))
                assertEquals(listOf("voice_enabled"), (0 until body.getJSONArray("config_skipped").length()).map {
                    body.getJSONArray("config_skipped").getString(it)
                })
                assertEquals("source", body.getString("panel_id"))
                assertEquals(false, body.getBoolean("state_unavailable"))
                assertEquals("contract-panel", fixture.config.panelId)
            }
        }
    }

    @Test fun `full mount refuses inverted automatic bounds before restore side effects`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
            fixture.config.setAutoBrightnessMinimumPercent(20)
            fixture.config.setAutoBrightnessMaximumPercent(60)
            testApplication {
                application { fixture.mount(this) }
                val invalidConfigs = listOf(
                    "\"auto_brightness_minimum_percent\":\"70\",\"auto_brightness_maximum_percent\":\"60\"",
                    "\"auto_brightness_minimum_percent\":\"70\"",
                    "\"auto_brightness_maximum_percent\":\"20\"",
                )
                for (suffix in listOf("?dry_run=1", "")) {
                    for (bounds in invalidConfigs) {
                        val response = client.post("/api/v1/restore$suffix") {
                            setBody("""{"kind":"ha-paneld-backup","schema":13,"config":{"friendly_name":"Must not apply",$bounds}}""")
                        }
                        val text = response.bodyAsText()
                        assertEquals(text, HttpStatusCode.UnprocessableEntity, response.status)
                        assertEquals("invalid backup config", JSONObject(text).getString("error"))
                        assertEquals(20, fixture.config.autoBrightnessMinimumPercent)
                        assertEquals(60, fixture.config.autoBrightnessMaximumPercent)
                        assertEquals("Contract panel", fixture.config.friendlyName)
                    }
                }
            }
        }
    }

    @Test fun `rootless preview reports unavailable live settings while retaining supported values`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel","ha_area":"Kitchen","network_adb":"true","silence_boot_chime":"false"}}""")
                }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, response.status)
                val body = JSONObject(text)
                assertEquals(2, body.getInt("config_keys"))
                assertEquals(listOf("network_adb", "silence_boot_chime"), (0 until body.getJSONArray("config_skipped").length()).map {
                    body.getJSONArray("config_skipped").getString(it)
                })
                assertTrue(body.getJSONArray("config_warnings").toString().contains("network_adb"))
                assertTrue(body.getJSONArray("config_warnings").toString().contains("silence_boot_chime"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertTrue(fixture.config.haArea.isEmpty())
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `unavailable live setting is still validated before a preview can skip it`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for (suffix in listOf("?dry_run=1", "")) {
                    val response = client.post("/api/v1/restore$suffix") {
                        setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"network_adb":"not-a-boolean","friendly_name":"Must not apply"}}""")
                    }
                    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                    assertEquals("invalid backup config", JSONObject(response.bodyAsText()).getString("error"))
                    assertEquals("contract-panel", fixture.config.panelId)
                    assertEquals(false, InstallProgress.running)
                }
            }
        }
    }

    @Test fun `rootless preview and real restore refuse requested network adb in hardened mode`() {
        PaneldServerHttpFixture().use { fixture ->
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            testApplication {
                application { fixture.mount(this) }
                for (suffix in listOf("?dry_run=1", "")) {
                    val response = client.post("/api/v1/restore$suffix") {
                        setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"network_adb":"true","friendly_name":"Must not apply"}}""")
                    }
                    val text = response.bodyAsText()
                    assertEquals(text, HttpStatusCode.Conflict, response.status)
                    assertEquals("network-adb-incompatible-with-hardened-mode", JSONObject(text).getString("error"))
                    assertEquals("contract-panel", fixture.config.panelId)
                    assertEquals(false, InstallProgress.running)
                }
            }
        }
    }

    @Test fun `full mount refuses malformed restore then releases admission for another request`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                repeat(2) {
                    val response = client.post("/api/v1/restore?dry_run=1") { setBody("not a backup") }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("not a Panel Assistant backup", JSONObject(response.bodyAsText()).getString("error"))
                }
                val invalid = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":1,"config":{"panel_id":{}}}""")
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
                assertEquals("invalid backup config", JSONObject(invalid.bodyAsText()).getString("error"))
                val migration = client.post("/api/v1/restore?mode=migration") { setBody("not a backup") }
                assertEquals(HttpStatusCode.Forbidden, migration.status)
                assertEquals("migration-restore-refused", JSONObject(migration.bodyAsText()).getString("error"))
            }
        }
    }

    @Test fun `full mount refuses a sealed backup with the wrong passphrase before parsing it`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val sealed = PanelBackup.seal(
                    """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Sealed panel"}}""".toByteArray(),
                    "correct horse",
                )
                val response = client.post("/api/v1/restore?dry_run=1") {
                    header("X-Backup-Passphrase", "battery staple")
                    setBody(sealed)
                }
                val body = JSONObject(response.bodyAsText())
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("wrong passphrase or corrupt bundle", body.optString("error"))
                assertEquals("restore-passphrase-or-bundle-invalid", body.optJSONObject("presentation")?.optString("code"))
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `full mount refuses a companion payload for a package the panel does not have`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody(
                        """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel"},""" +
                            """"companion":{"pkg":"io.homeassistant.companion.android.minimal",""" +
                            """"files":[{"rel":"databases/HomeAssistantDB","b64":"ZGF0YQ=="}]}}""",
                    )
                }
                val body = JSONObject(response.bodyAsText())
                assertEquals(body.toString(), HttpStatusCode.BadRequest, response.status)
                assertEquals("companion-unsupported-package", body.optJSONObject("presentation")?.optString("code"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `full mount refuses a profile catalog the panel cannot restore`() {
        PaneldServerHttpFixture().use { fixture ->
            val status = ProfileStatus(1, ProfileSelection.Auto, null, ProfileActivationState(ProfileActivationPhase.ACTIVE))
            // The interface's own planBackupRestore answers an invalid plan.
            val admin = object : ProfileAdmin {
                override fun schema() = error("unused")
                override fun drivers() = error("unused")
                override fun status() = status
                override fun list() = error("unused")
                override fun preview(rawYaml: String) = error("unused")
                override fun importProfile(rawYaml: String, previewToken: String) = error("unused")
                override fun exportProfile(ref: ProfileRef) = error("unused")
                override fun select(selection: ProfileSelection, expectedCatalogRevision: Long) = error("unused")
                override fun rollbackToLastKnownGood(expectedCatalogRevision: Long) = error("unused")
                override fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long) = error("unused")
                override fun restoreBackup(payload: ProfileBackup, expectedCatalogRevision: Long) =
                    error("An unrestorable catalog must never be applied")
            }
            val profiles = ProfileBackup(revisions = emptyList(), selection = ProfileSelection.Auto, active = null, lastKnownGood = null).toJson()
            testApplication {
                application {
                    fixture.mount(this)
                    // After mounting: the restore route reads its profile admin per request.
                    PaneldServer::class.java.getDeclaredField("profileAdmin").apply { isAccessible = true }.set(fixture.server, admin)
                }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel"},"profiles":$profiles}""")
                }
                val body = JSONObject(response.bodyAsText())
                assertEquals(body.toString(), HttpStatusCode.UnprocessableEntity, response.status)
                assertEquals("restore-profile-catalog-not-restorable", body.optJSONObject("presentation")?.optString("code"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }
}
