package io.panelassistant.android.sensors

import io.panelassistant.android.http.HaAreaProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry shapes a panel meets in the field. Fixtures follow the live
 * registry: a Panel Assistant device carries only `(panel_assistant, <entry_id>)`.
 */
class HaPanelDeviceMatcherTest {
    private val entry = "01J00000000000000000000AAA"
    private val otherEntry = "01J00000000000000000000BBB"

    // --- which device is the panel ------------------------------------------------------------

    @Test fun `panel_assistant only resolves through the proven entry`() {
        val devices = listOf(paDevice("pa-device", entry), paDevice("other-pa-device", otherEntry))

        assertEquals("pa-device", found(devices, entries = setOf(entry)))
        assertEquals(
            "without the proof a Panel Assistant device is nobody's",
            HaPanelDeviceMatcher.Match.Missing,
            HaPanelDeviceMatcher.preferred(devices, emptySet()),
        )
    }

    @Test fun `two proven devices are ambiguous, never a guess`() {
        val devices = listOf(paDevice("pa-device", entry), paDevice("other-pa-device", otherEntry))

        assertEquals(
            HaPanelDeviceMatcher.Match.Ambiguous,
            HaPanelDeviceMatcher.preferred(devices, setOf(entry, otherEntry)),
        )
    }

    @Test fun `a registration of the panel by another integration is never its device`() {
        // Identifiers the panel once published itself still sit on old device rows; only the proven Panel
        // Assistant entry identifies the panel now.
        val stale = listOf(
            otherDevice("uid-device", "ha-paneld-uid-abc"),
            otherDevice("legacy-device", "ha-paneld-panel"),
            otherDevice("merged-device", "ha-paneld-aid-9f86d081884c7d65"),
        )

        assertEquals(HaPanelDeviceMatcher.Match.Missing, HaPanelDeviceMatcher.preferred(stale, emptySet()))
        assertEquals("pa-device", found(stale + paDevice("pa-device", entry), entries = setOf(entry)))
        assertEquals(
            listOf("pa-device"),
            HaPanelDeviceMatcher.all(stale + paDevice("pa-device", entry), setOf(entry)).map { it.getString("id") },
        )
    }

    // --- both consumers use it ------------------------------------------------------------------

    @Test fun `presence discovery works for a panel registered only by Panel Assistant`() {
        val devices = response(paDevice("pa-device", entry, area = "study"), otherDevice("motion-device", "motion", "study"))
        val areas = response(JSONObject().put("area_id", "study").put("name", "Study"))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(display("binary_sensor.den_proximity", "pa-device", "panel_assistant"))
            .put(display("binary_sensor.study_motion", "motion-device", "zha"))))
        val states = JSONArray()
            .put(state("binary_sensor.den_proximity", "on", "occupancy"))
            .put(state("binary_sensor.study_motion", "off", "motion"))

        val projection = project(devices, areas, entities, states)

        assertEquals("study", projection.panelAreaId)
        assertEquals(
            "the panel's own proximity must not become an Area source",
            listOf("binary_sensor.study_motion"),
            projection.candidates.map { it.entityId },
        )
    }

    @Test fun `the Area catalogue finds a panel registered only by Panel Assistant`() {
        val devices = response(paDevice("pa-device", entry, area = "study"))
        val areas = listOf(HaAreaProtocol.HaArea("study", "Study"))

        val area = HaAreaProtocol.panelDeviceArea(devices, areas, setOf(entry))

        assertTrue(area.found)
        assertEquals("pa-device", area.deviceId)
        assertEquals("Study", area.areaName)
        assertFalse(HaAreaProtocol.panelDeviceArea(devices, areas, emptySet()).found)
    }

    private fun found(devices: List<JSONObject>, entries: Set<String>): String {
        val match = HaPanelDeviceMatcher.preferred(devices, entries)
        assertTrue("expected exactly one device, got $match", match is HaPanelDeviceMatcher.Match.Found)
        return (match as HaPanelDeviceMatcher.Match.Found).device.getString("id")
    }

    /** Asserts the projection succeeds, so a lost device match fails as an assertion, not an exception. */
    private fun project(devices: JSONObject, areas: JSONObject, entities: JSONObject, states: JSONArray) =
        runCatching { HaPresenceProtocol.projectArea(devices, areas, entities, states, setOf(entry)) }
            .also { assertTrue("projection failed: ${it.exceptionOrNull()?.message}", it.isSuccess) }
            .getOrThrow()

    private fun display(entityId: String, deviceId: String, platform: String) = JSONObject()
        .put("ei", entityId).put("di", deviceId).put("pl", platform)

    private fun otherDevice(id: String, identifier: String, area: String = "") = device(id, area, "zha", identifier)

    private fun paDevice(id: String, entryId: String, area: String = "") = device(id, area, "panel_assistant", entryId)

    private fun device(id: String, area: String, domain: String, identifier: String) = JSONObject()
        .put("id", id).put("area_id", area)
        .put("identifiers", JSONArray().put(JSONArray().put(domain).put(identifier)))

    private fun response(vararg rows: JSONObject) = JSONObject().put("result", JSONArray().apply { rows.forEach(::put) })

    private fun state(entity: String, value: String, deviceClass: String) = JSONObject()
        .put("entity_id", entity).put("state", value)
        .put("attributes", JSONObject().put("device_class", deviceClass))
}
