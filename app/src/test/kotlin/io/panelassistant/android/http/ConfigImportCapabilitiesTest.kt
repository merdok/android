package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.kioskSetting
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Complete HTTP import, production kiosk admission and durable Config readback. */
class ConfigImportCapabilitiesTest {
    @Test fun previewAndImportSkipUnsupportedKioskOnButRetainSupportedSettings() {
        for ((eligible, directSu) in listOf(false to false, true to false, false to true, true to true)) {
            withRealConfigFixture { config, fixture ->
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
                assertTrue(config.commitKioskLock(false))
                val available = eligible && directSu
                var actuated = false
                val coordinator = kioskSetting(
                    canEnable = { available }, actuate = { actuated = true; true }, persist = config::commitKioskLock,
                )
                field(fixture.server, "applySetting", { key: String, value: String ->
                    check(key == "kiosk_lock")
                    if (coordinator(value == "true")) LiveSettingRequestOutcome.APPLIED
                    else LiveSettingRequestOutcome.FAILED_PENDING
                })
                capabilities(fixture, Capabilities(appCanSu = eligible, networkAdb = directSu, canControlBootChime = true))
                val bundle = ConfigBundle.fromValues(mapOf("friendly_name" to "Imported", "kiosk_lock" to "true")).serialize()
                testApplication {
                    application { fixture.mount(this) }
                    val preview = client.post("/api/v1/config/import?dry_run=1") { setBody(bundle) }
                    assertEquals(HttpStatusCode.OK, preview.status)
                    val json = JSONObject(preview.bodyAsText())
                    val changes = json.getJSONArray("changes")
                    assertEquals(available, (0 until changes.length()).any { changes.getJSONObject(it).getString("key") == "kiosk_lock" })
                    assertEquals(if (available) emptyList() else listOf("kiosk_lock"), strings(json, "skipped"))
                    assertEquals("Before", config.friendlyName)
                    assertFalse(actuated)
                    val applied = client.post("/api/v1/config/import?expected_cfg=" + json.getString("expected_cfg")) { setBody(bundle) }
                    assertEquals(HttpStatusCode.OK, applied.status)
                    val receipt = JSONObject(applied.bodyAsText())
                    assertEquals(if (available) "applied" else "partial", receipt.getString("status"))
                    assertEquals(if (available) emptyList() else listOf("kiosk_lock"), strings(receipt, "skipped"))
                    assertEquals("Imported", config.friendlyName)
                    assertEquals(available, config.kioskLock)
                    assertEquals(available, actuated)
                }
            }
        }
    }

    @Test fun rootlessImportAdmitsKioskOffCleanup() = withRealConfigFixture { config, fixture ->
        assertTrue(config.commitKioskLock(true))
        var cleanup = false
        val coordinator = kioskSetting(
            canEnable = { false }, actuate = { cleanup = true; false }, persist = config::commitKioskLock,
        )
        field(fixture.server, "applySetting", { _: String, value: String ->
            if (coordinator(value == "true")) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.FAILED_PENDING
        })
        capabilities(fixture, Capabilities())
        val bundle = ConfigBundle.fromValues(mapOf("kiosk_lock" to "false")).serialize()
        testApplication {
            application { fixture.mount(this) }
            val response = client.post("/api/v1/config/import") { setBody(bundle) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("applied", JSONObject(response.bodyAsText()).getString("status"))
            assertFalse(config.kioskLock)
            assertTrue(cleanup)
        }
    }

    @Test fun lateLiveRefusalReturnsJsonAndNamesAlreadyCommittedOrdinarySettings() {
        for (deferred in listOf(false, true)) {
            withRealConfigFixture { config, fixture ->
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
                config.setRaw(requireNotNull(SettingsRegistry.spec("ha_area")), "Hall")
                assertTrue(config.commitKioskLock(false))
                capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
                val authority = LiveSettingAuthority(setOf("ha_area"))
                val coordinator = kioskSetting(
                    canEnable = { false }, actuate = { error("root disappeared before actuation") }, persist = config::commitKioskLock,
                )
                field(fixture.server, "applySetting", { key: String, value: String ->
                    if (key == "ha_area") {
                        authority.applyOrQueueOutcome(key, value, null) { appliedKey, appliedValue, _ ->
                            if (deferred) LiveSettingApplyResult.DEFERRED else {
                                config.setRaw(requireNotNull(SettingsRegistry.spec(appliedKey)), appliedValue)
                                LiveSettingApplyResult.APPLIED
                            }
                        }
                    } else {
                        check(key == "kiosk_lock")
                        if (coordinator(value == "true")) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.FAILED_PENDING
                    }
                })
                testApplication {
                    application { fixture.mount(this) }
                    val bundle = ConfigBundle.fromValues(linkedMapOf(
                        "friendly_name" to "Imported", "ha_area" to "Kitchen", "kiosk_lock" to "true",
                    )).serialize()
                    val response = client.post("/api/v1/config/import") { setBody(bundle) }
                    val text = response.bodyAsText()
                    assertTrue(response.headers["Content-Type"].orEmpty().startsWith("application/json"), text)
                    assertTrue(text.trim().startsWith("{"), text)
                    val receipt = JSONObject(text)
                    assertTrue(receipt.has("applied"), text)
                    assertEquals("partial", receipt.getString("status"))
                    assertEquals(if (deferred) listOf("friendly_name") else listOf("friendly_name", "ha_area"), strings(receipt, "applied"))
                    assertEquals(if (deferred) "Hall" else "Kitchen", config.haArea)
                    assertEquals(if (deferred) mapOf("ha_area" to "Kitchen") else emptyMap(), authority.pendingSnapshot())
                    assertTrue(receipt.getJSONArray("errors").toString().contains("kiosk_lock"))
                    assertEquals("Imported", config.friendlyName)
                    assertFalse(config.kioskLock)
                }
            }
        }
    }

    @Test fun hardenedImportPreviewAndApplyRefuseOriginalAdbIntentBeforeCapabilitySkipping() = withRealConfigFixture { config, fixture ->
        assertTrue(config.setSecurityMode(Config.SecurityMode.HARDENED))
        capabilities(fixture, Capabilities())
        testApplication {
            application { fixture.mount(this) }
            val bundle = ConfigBundle.fromValues(mapOf("friendly_name" to "Must not apply", "network_adb" to "true")).serialize()
            for (suffix in listOf("?dry_run=1", "")) {
                val response = client.post("/api/v1/config/import$suffix") { setBody(bundle) }
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals("network-adb-incompatible-with-hardened-mode", JSONObject(response.bodyAsText()).getString("error"))
                assertFalse(config.friendlyName == "Must not apply")
            }
        }
    }

    private fun capabilities(fixture: PaneldServerHttpFixture, caps: Capabilities) {
        val observations = readField(fixture.server, "managementObservations") as ManagementObservations
        val previous = requireNotNull(observations.snapCache.peek())
        observations.snapCache.set(ManagementSnapshot(
            previous.facts, previous.live, caps, previous.capabilityRows, previous.privilege,
            previous.densityCur, previous.densityBase, previous.fontScale, previous.wifiChronic,
        ))
    }

    private fun strings(json: JSONObject, name: String): List<String> = json.getJSONArray(name).let { array ->
        (0 until array.length()).map(array::getString)
    }

    private fun withRealConfigFixture(block: (Config, PaneldServerHttpFixture) -> Unit) = withFullReadServer(block)
    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun readField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
