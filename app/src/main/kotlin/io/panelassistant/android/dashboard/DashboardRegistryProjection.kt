package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject

internal data class DashboardRegistryProjection(
    val metadata: Map<String, String>,
    val areaRegistryEntities: Map<String, Set<String>>,
    val complete: Boolean,
)

/** Validate and enrich the registry snapshot used by registry-backed dashboard selectors. */
internal fun projectDashboardRegistries(
    entityResponse: JSONObject?,
    areaResponse: JSONObject?,
    deviceResponse: JSONObject?,
    floorResponse: JSONObject?,
    labelResponse: JSONObject?,
    requirements: DashboardConfigurationLint.RegistryRequirements =
        DashboardConfigurationLint.RegistryRequirements(
            entities = true,
            areas = true,
            devices = true,
            floors = true,
            labels = true,
        ),
): DashboardRegistryProjection {
    val metadata = mutableMapOf<String, String>()
    var complete = true
    val entries = if (requirements.entities) {
        entityResponse?.optJSONObject("result")?.optJSONArray("entities")
            ?: entityResponse?.optJSONArray("result")
    } else null
    if (requirements.entities && entries == null) complete = false
    val entityRows = entries ?: JSONArray()
    for (i in 0 until entityRows.length()) {
        val row = entityRows.optJSONObject(i)
        val id = row?.let { it.optString("ei").ifBlank { it.optString("entity_id") } }?.lowercase()
        if (row == null || id == null || !EntityFilterProtocol.isEntityId(id)) complete = false
        else if (metadata.put(id, row.toString()) != null) complete = false
    }

    val areas = if (requirements.areas) areaResponse?.optJSONArray("result") else null
    val devices = if (requirements.devices) deviceResponse?.optJSONArray("result") else null
    val floors = if (requirements.floors) floorResponse?.optJSONArray("result") else null
    val labels = if (requirements.labels) labelResponse?.optJSONArray("result") else null

    fun rows(
        required: Boolean,
        array: JSONArray?,
        requireName: Boolean,
        vararg idKeys: String,
    ): Map<String, JSONObject> {
        if (!required) return emptyMap()
        if (array == null) {
            complete = false
            return emptyMap()
        }
        val out = mutableMapOf<String, JSONObject>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i)
            val id = row?.let { objectValue ->
                idKeys.firstNotNullOfOrNull { key -> objectValue.optString(key).takeIf(String::isNotBlank) }
            }?.lowercase()
            val name = row?.opt("name") as? String
            if (row == null || id == null || !id.matches(PROJECTED_REGISTRY_ID) || requireName &&
                (name.isNullOrBlank() || name.length > PROJECTED_REGISTRY_NAME_MAX || name.any(Char::isISOControl))
            ) {
                complete = false
            } else if (out.put(id, row) != null) complete = false
        }
        return out
    }

    val areaRows = rows(requirements.areas, areas, true, "area_id", "id")
    val deviceRows = rows(requirements.devices, devices, false, "id", "device_id")
    val floorRows = rows(requirements.floors, floors, true, "floor_id", "id")
    val labelRows = rows(requirements.labels, labels, true, "label_id", "id")

    fun labelIds(row: JSONObject?, vararg keys: String): Set<String> = buildSet {
        for (key in keys) {
            if (row == null || !row.has(key)) continue
            val value = row.opt(key)
            if (value is JSONArray) {
                for (index in 0 until value.length()) {
                    val id = (value.opt(index) as? String)?.lowercase()
                    if (id == null || !id.matches(PROJECTED_REGISTRY_ID)) complete = false else add(id)
                }
            } else if (value is String) {
                val id = value.lowercase()
                if (!id.matches(PROJECTED_REGISTRY_ID)) complete = false else add(id)
            } else {
                complete = false
            }
        }
    }

    metadata.replaceAll { _, raw ->
        val entity = JSONObject(raw)
        val deviceId = entity.optString("di").ifBlank { entity.optString("device_id") }.lowercase()
        val device = if (requirements.devices) deviceRows[deviceId] else null
        if (requirements.devices && deviceId.isNotBlank() && device == null) complete = false
        val area = if (requirements.areas) {
            val areaId = entity.optString("ai").ifBlank { entity.optString("area_id") }
                .ifBlank { device?.optString("area_id").orEmpty() }.lowercase()
            val row = areaRows[areaId]
            if (areaId.isNotBlank() && row == null) complete = false
            if (areaId.isNotBlank()) entity.put("ai", areaId)
            row?.optString("name")?.takeIf(String::isNotBlank)?.let { entity.put("an", it) }
            row
        } else null
        if (requirements.floors) {
            val floorId = area?.optString("floor_id").orEmpty().lowercase()
            val floor = floorRows[floorId]
            if (floorId.isNotBlank() && floor == null) complete = false
            if (floorId.isNotBlank()) entity.put("fi", floorId)
            floor?.optString("name")?.takeIf(String::isNotBlank)?.let { entity.put("fn", it) }
        }
        if (requirements.labels) {
            val inheritedLabels = labelIds(entity, "lb", "labels", "label_ids") +
                labelIds(device, "labels", "label_ids")
            if (inheritedLabels.isNotEmpty()) entity.put("lb", JSONArray(inheritedLabels.sorted()))
            val labelNames = inheritedLabels.mapNotNullTo(sortedSetOf()) { labelId ->
                val label = labelRows[labelId]
                if (label == null) complete = false
                label?.optString("name")?.takeIf(String::isNotBlank)
            }
            if (labelNames.isNotEmpty()) entity.put("ln", JSONArray(labelNames))
        }
        entity.toString()
    }
    val areaRegistryEntities = if (requirements.areas) areaRows.mapValues { (_, area) ->
        setOf("temperature_entity_id", "humidity_entity_id").mapNotNullTo(sortedSetOf()) { key ->
            val raw = area.optString(key).lowercase()
            if (raw.isNotBlank() && !EntityFilterProtocol.isEntityId(raw)) complete = false
            raw.takeIf(EntityFilterProtocol::isEntityId)
        }
    } else emptyMap()
    return DashboardRegistryProjection(metadata, areaRegistryEntities, complete)
}


private val PROJECTED_REGISTRY_ID = Regex("^[a-z0-9_-]+$")

private const val PROJECTED_REGISTRY_NAME_MAX = 1_024
