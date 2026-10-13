package io.panelassistant.android.sensors

import org.json.JSONObject

/**
 * The one definition of which Home Assistant device rows are this panel. Presence discovery, the
 * presence Area prerequisite and the Area catalogue all ask it; two copies of this rule drifted once
 * already, when a panel registered only by Panel Assistant matched in neither.
 *
 * Panel Assistant registers the panel's device under one config-entry-scoped identifier,
 * `(panel_assistant, <entry_id>)`. The `hello` reply names the entry, and the panel keeps it with the
 * connection it learned ([io.panelassistant.android.HaConnectionRoutes]); with no entry learned, no row
 * is this panel.
 *
 * Identifiers other integrations once registered for the panel are deliberately never matched: only the
 * entry the hello named identifies its device row.
 */
internal object HaPanelDeviceMatcher {
    const val PANEL_ASSISTANT_DOMAIN = "panel_assistant"

    sealed interface Match {
        data class Found(val device: JSONObject) : Match
        data object Missing : Match
        data object Ambiguous : Match
    }

    /** Every device row that is this panel. It is what excludes the panel's own entities. */
    fun all(devices: List<JSONObject>, panelAssistantEntryIds: Set<String>): List<JSONObject> =
        devices.filter { isPanel(it, panelAssistantEntryIds) }

    /** The single device row that speaks for this panel, which must be unique. */
    fun preferred(devices: List<JSONObject>, panelAssistantEntryIds: Set<String>): Match {
        val matches = all(devices, panelAssistantEntryIds)
        return when (matches.size) {
            0 -> Match.Missing
            1 -> Match.Found(matches.single())
            else -> Match.Ambiguous
        }
    }

    private fun isPanel(device: JSONObject, panelAssistantEntryIds: Set<String>): Boolean =
        panelAssistantEntryIds.any { hasIdentifier(device, PANEL_ASSISTANT_DOMAIN, it) }

    private fun hasIdentifier(device: JSONObject, domain: String, identifier: String): Boolean =
        identifiers(device).any { (d, value) -> d == domain && value == identifier }

    private fun identifiers(device: JSONObject): List<Pair<String, String>> {
        val identifiers = device.optJSONArray("identifiers") ?: return emptyList()
        return (0 until identifiers.length()).mapNotNull { index ->
            val tuple = identifiers.optJSONArray(index) ?: return@mapNotNull null
            if (tuple.length() == 2) tuple.optString(0) to tuple.optString(1) else null
        }
    }
}
