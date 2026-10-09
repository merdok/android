package io.panelassistant.android.panelassistant

import io.panelassistant.android.state.SoftwareUpdates
import io.panelassistant.android.config.ChannelOption
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PanelAssistantChannelCatalogTest {
    @Test fun cameraControlDescribesAnOrdinaryEnabledCameraAndReportsBothPowerStates() {
        val descriptor = PanelAssistantChannelCatalog.describe("camera_enabled")!!
        val json = descriptor.toJson()
        assertEquals("camera", json.getString("platform"))
        assertEquals("camera", json.getString("translation_key"))
        assertEquals("camera", json.getString("unique_suffix"))
        assertTrue(json.getBoolean("enabled_default"))
        assertTrue(json.isNull("entity_category"))
        assertEquals(PanelAssistantWireValue.Known(false), PanelAssistantValueTranslation.translate(descriptor, StateConverger.Observation.Known("OFF")))
        assertEquals(PanelAssistantWireValue.Known(true), PanelAssistantValueTranslation.translate(descriptor, StateConverger.Observation.Known("ON")))
    }

    // Source-text reason: the registration sites in PanelStateCore.kt are the channel list; no runtime enumerates every family.

    @Test fun mediaIsAnEnabledMediaPlayerTheNativeTransportCommands() {
        val json = PanelAssistantChannelCatalog.describe("media")!!.toJson()
        assertEquals("media_player", json.getString("platform"))
        assertEquals("media", json.getString("translation_key"))
        assertEquals("media", json.getString("unique_suffix"))
        assertTrue(json.getBoolean("enabled_default"))
        assertTrue(json.isNull("entity_category"))
        assertTrue("media_player" in PanelAssistantCommandTranslation.COMMANDABLE_PLATFORMS)
        assertTrue("the converger registers media", "media" in convergerChannels())
    }

    @Test fun everyConvergerChannelIsDescribedOrFolded() {
        val keys = convergerChannels()
        // The scan must find the channels it exists to guard, or an empty scan would pass vacuously.
        assertTrue("scan found only ${keys.size} channels: $keys", keys.size >= 40)
        assertTrue(keys.containsAll(listOf("screen", "relay1", "button_led64", "cpu_governor", "room_humidity", "diag_wifi_outages_attributes")))
        val undescribed = keys.filter { key ->
            val wire = PanelAssistantChannelCatalog.wireChannel(key)
            if (wire == null) key !in PanelAssistantChannelCatalog.FOLDED else PanelAssistantChannelCatalog.describe(wire) == null
        }
        assertEquals("converger channels with no descriptor", emptyList<String>(), undescribed)
        assertNull(PanelAssistantChannelCatalog.describe("future_leaf"))
    }

    @Test fun attributeChannelsFoldIntoTheirParentsAndUpdateChannelsTakeTheirWireIds() {
        assertNull(PanelAssistantChannelCatalog.wireChannel("storage_health_attributes"))
        assertNull(PanelAssistantChannelCatalog.wireChannel("diag_wifi_outages_attributes"))
        assertEquals("storage_health", PanelAssistantChannelCatalog.FOLDED["storage_health_attributes"])
        assertEquals("diag_wifi_outages_24h", PanelAssistantChannelCatalog.FOLDED["diag_wifi_outages_attributes"])
        assertEquals("update_paneld", PanelAssistantChannelCatalog.wireChannel("software_update_paneld"))
        assertEquals("update_companion", PanelAssistantChannelCatalog.wireChannel("software_update_companion"))
        assertEquals("screen", PanelAssistantChannelCatalog.wireChannel("screen"))
    }

    @Test fun renamingADisplayLabelChangesNoWireCode() {
        val renamedSelects = SettingsRegistry.haCapable().filter { it.ha!!.facts.options != null }
        assertTrue(renamedSelects.map { it.key }.containsAll(listOf("navbar_mode", "cpu_governor")))
        for (spec in renamedSelects) {
            val entity = spec.ha!!
            val renamed = entity.facts.options!!.map { ChannelOption(it.code, "${it.label} (renamed)") }
            val before = PanelAssistantChannelCatalog.describe(spec)
            val after = PanelAssistantChannelCatalog.describe(spec.copy(ha = entity.copy(facts = entity.facts.copy(options = renamed))))
            assertEquals("${spec.key} wire codes", before.options, after.options)
            assertEquals("${spec.key} descriptor json", before.toJson().toString(), after.toJson().toString())
            for (option in renamed) {
                assertEquals("${spec.key} ${option.code} state", PanelAssistantWireValue.Known(option.code),
                    PanelAssistantValueTranslation.translate(after, StateConverger.Observation.Known(option.label)))
                if (after.platform == "select") {
                    assertEquals("${spec.key} ${option.code} command", option.label, PanelAssistantCommandTranslation.payload(after, option.code))
                }
            }
        }
    }

    @Test fun uniqueSuffixIsTheHistoricalUniqueIdAfterThePanelPrefix() {
        assertEquals("ha_paneld_update", PanelAssistantChannelCatalog.describe("update_paneld")?.uniqueSuffix)
        assertEquals("ha_companion_update", PanelAssistantChannelCatalog.describe("update_companion")?.uniqueSuffix)
        assertEquals("navbar", PanelAssistantChannelCatalog.describe("navbar")?.uniqueSuffix)
        // A registry entity takes its object suffix, not its channel name, when the two differ.
        val navbar = requireNotNull(SettingsRegistry.spec("navbar_mode"))
        val renamed = navbar.copy(ha = navbar.ha!!.copy(objectSuffix = "navigation_bar", channel = "navbar"))
        assertEquals("navigation_bar", PanelAssistantChannelCatalog.describe(renamed).uniqueSuffix)
    }

    @Test fun familiesCarryTheirFamilyAndIndexAndShareOneTranslationKey() {
        val relay = described("relay3")
        assertEquals(listOf("switch", "relay", "relay", "3", "relay3"), listOf(relay.platform, relay.translationKey, relay.family, relay.index.toString(), relay.uniqueSuffix))
        val led = described("button_led2")
        assertEquals(listOf("light", "button_led", "button_led", "2"), listOf(led.platform, led.translationKey, led.family, led.index.toString()))
        assertNull(PanelAssistantChannelCatalog.describe("relay0"))
    }

    @Test fun retiredAutoUpdateSettingsAreAbsentWhileExplicitUpdateDescriptorsRemain() {
        listOf("self_update", "update_channel", "companion_auto_update", "companion_update_channel", "webview_auto_update").forEach { channel ->
            assertNull(channel, PanelAssistantChannelCatalog.describe(channel))
        }
        assertEquals(PanelAssistantValueKind.UPDATE, PanelAssistantChannelCatalog.describe("update_paneld")?.kind)
        assertEquals(PanelAssistantValueKind.UPDATE, PanelAssistantChannelCatalog.describe("update_companion")?.kind)
    }

    @Test fun selectsAndClosedSensorsCarrySnakeCaseOptionCodes() {
        assertEquals(listOf("off", "always_on", "swipe_reveal", "native"), PanelAssistantChannelCatalog.describe("navbar")?.options)
        assertEquals(listOf("performance", "efficiency", "auto"), PanelAssistantChannelCatalog.describe("cpu_governor")?.options)
        assertEquals(listOf("unchecked", "healthy", "warning", "critical", "database_failure"), PanelAssistantChannelCatalog.describe("storage_health")?.options)
        assertEquals(PanelAssistantValueKind.TEXT, PanelAssistantChannelCatalog.describe("diag_wifi_ssid")?.kind)
        assertEquals(PanelAssistantValueKind.NUMBER, PanelAssistantChannelCatalog.describe("diag_wifi_rssi")?.kind)
    }

    @Test fun enabledDefaultFollowsTheExposureDefaultAndWireOnlySettingsStartDisabled() {
        assertEquals(false, PanelAssistantChannelCatalog.describe("diag_cpu")?.enabledDefault)
        assertEquals(false, PanelAssistantChannelCatalog.describe("watchdog")?.enabledDefault)
        assertEquals(true, PanelAssistantChannelCatalog.describe("relay1")?.enabledDefault)
        assertNotNull(PanelAssistantChannelCatalog.describe("illuminance")?.takeIf { it.enabledDefault })
    }

    @Test fun descriptorJsonHasEveryRequiredFieldWithTheIntegrationsGrammar() {
        val code = Regex("^[a-z][a-z0-9_]{0,63}$")
        val suffix = Regex("^[a-z0-9][a-z0-9_]{0,47}$")
        for (key in convergerChannels()) {
            val wire = PanelAssistantChannelCatalog.wireChannel(key) ?: continue
            val json = described(wire).toJson()
            assertTrue(json.toString(), code.matches(json.getString("translation_key")))
            assertTrue(json.toString(), suffix.matches(json.getString("unique_suffix")))
            assertEquals(json.toString(), json.isNull("family"), json.isNull("index"))
            if (json.getString("platform") == "select") assertTrue(json.toString(), !json.isNull("options"))
        }
    }

    @Test fun eachChannelsDescriptorIsBuiltOnceAndReturnedAgainForThatChannelOnly() {
        // A registry-backed channel and a family channel are both built on demand rather than held in a table.
        val screen = described("screen")
        val relay = described("relay7")
        assertEquals(listOf("screen", "relay7"), listOf(screen.channel, relay.channel))
        assertSame(screen, PanelAssistantChannelCatalog.describe("screen"))
        assertSame(relay, PanelAssistantChannelCatalog.describe("relay7"))
        assertNull(PanelAssistantChannelCatalog.describe("future_leaf"))
        assertNull(PanelAssistantChannelCatalog.describe("future_leaf"))
    }

    /** Asserts before dereferencing, so a missing descriptor fails as an assertion rather than an error. */
    private fun described(wire: String): PanelAssistantChannelDescriptor {
        val descriptor = PanelAssistantChannelCatalog.describe(wire)
        assertNotNull("no descriptor for $wire", descriptor)
        return descriptor!!
    }



    companion object {
        /** Every channel the state core registers with its converger, read from the registration sites. */
        fun convergerChannels(): List<String> {
            val core = TestSources.kotlin("PanelStateCore.kt").readText()
            val converger = core.substringAfter("private fun createStateConverger()").substringBefore("fun addStateSink(")
            val capabilities = core.substringAfter("fun ensureCapabilityChannels(").substringBefore("fun start()")
            fun list(name: String) = Regex("""\"([a-z0-9_]+)\"""").findAll(
                core.substringAfter("val $name = listOf(").substringBefore(")"),
            ).map { it.groupValues[1] }.toList()
            val literals = Regex("""channel\(\s*"([a-z0-9_]+)"""").findAll(converger).map { it.groupValues[1] }.toList()
            require(converger.contains("SoftwareUpdates.stateChannelKey(component)"))
            val families = Regex("""register\("([a-z_]+?)(\${'$'}n)?"""").findAll(capabilities).flatMap { match ->
                if (match.groupValues[2].isEmpty()) sequenceOf(match.groupValues[1])
                else sequenceOf("${match.groupValues[1]}1", "${match.groupValues[1]}64")
            }.toList()
            return (literals + list("DIAG_KEYS") + list("ROOM_KEYS") + families +
                listOf("software_update_paneld", "software_update_companion")).distinct()
        }
    }
}
