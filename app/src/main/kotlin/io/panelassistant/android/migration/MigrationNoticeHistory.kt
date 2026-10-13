package io.panelassistant.android.migration

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.persistence.ConfigVault

/**
 * Carry only positive notice history, never the legacy transport authority or update-owner lease: that
 * Panel Assistant was seen, and that the store came from a broker-era install, which is who the notice
 * is for.
 */
internal fun migrationNoticeHistoryRows(
    rows: List<ConfigVault.StateRow>,
    migrationRestore: Boolean,
    sameDevice: Boolean,
): List<ConfigVault.StateRow> {
    if (!migrationRestore || !sameDevice) return emptyList()
    val config = rows.filter { it.namespace == "config" }

    val seenAt = config.asSequence()
        .filter { row ->
            when (row.key) {
                "panel_assistant_authority" ->
                    row.type == "string" &&
                        row.valueText in setOf("shadow", "native")
                "panel_assistant_update_owner_seen_ms" ->
                    row.type == "long" && (row.valueText?.toLongOrNull() ?: 0L) > 0L
                "migration_notice_connection_seen" -> row.type == "boolean" && row.valueText == "1"
                else -> false
            }
        }
        .map { it.updatedAt }
        .maxOrNull()
    val originAt = config.asSequence()
        .filter { row ->
            SettingsRegistry.isBrokerEra(row.key) ||
                (row.key == "migration_notice_legacy_origin" && row.type == "boolean" && row.valueText == "1")
        }
        .map { it.updatedAt }
        .maxOrNull()

    return listOfNotNull(
        seenAt?.let { ConfigVault.StateRow("config", "migration_notice_connection_seen", "boolean", "1", it) },
        originAt?.let { ConfigVault.StateRow("config", "migration_notice_legacy_origin", "boolean", "1", it) },
    )
}
