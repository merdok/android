package io.panelassistant.android.migration

import io.panelassistant.android.persistence.ConfigVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationNoticeHistoryTest {
    @Test fun eachAcceptedAuthorityBecomesNoticeHistoryOnly() {
        for (authority in listOf("shadow", "native")) {
            val source = row("panel_assistant_authority", "string", authority, updatedAt = 11L)
            assertEquals(listOf(noticeRow(11L), originRow(11L)), carry(listOf(source)))
        }
    }

    @Test fun positiveUpdateOwnerTimestampBecomesNoticeHistoryWithoutCarryingTheLease() {
        val source = row("panel_assistant_update_owner_seen_ms", "long", "1234", updatedAt = 17L)
        assertEquals(listOf(noticeRow(17L), originRow(17L)), carry(listOf(source)))
    }

    @Test fun multiplePositiveSourcesStillProduceOneNoticeOnlyRow() {
        val sources = listOf(
            row("panel_assistant_authority", "string", "native", updatedAt = 11L),
            row("panel_assistant_update_owner_seen_ms", "long", "1234", updatedAt = 17L),
        )
        assertEquals(listOf(noticeRow(17L), originRow(17L)), carry(sources))
    }

    @Test fun aBrokerEraStoreCarriesItsOriginWithoutConnectionEvidence() {
        val sources = listOf(
            row("panel_assistant_mqtt_discovery", "string", "withdraw", updatedAt = 19L),
            row("mqtt_broker", "string", "tcp://ha.local:1883", updatedAt = 13L),
            row("panel_assistant_authority", "string", "mqtt", updatedAt = 7L),
        )
        assertEquals(listOf(originRow(19L)), carry(sources))
    }

    @Test fun originSurvivesAnotherMigrationAndAFreshStoreHasNone() {
        val first = carry(listOf(row("last_discovery_version", "string", "0.9.11", updatedAt = 29L)))
        assertEquals(listOf(originRow(29L)), first)
        assertEquals(first, carry(first))
        assertTrue(carry(listOf(row("panel_id", "string", "kitchen"), row("ha_url", "string", "http://ha"))).isEmpty())
    }

    @Test fun noticeHistorySurvivesAnotherMigrationWithoutChangingItsMeaning() {
        val first = carry(listOf(row("panel_assistant_authority", "string", "shadow", updatedAt = 23L)))
        assertEquals(listOf(noticeRow(23L), originRow(23L)), first)
        assertEquals(first, carry(first))
    }

    @Test fun ordinaryOrUnprovenRestoreCarriesNothing() {
        val source = listOf(row("panel_assistant_authority", "string", "native"))
        assertTrue(migrationNoticeHistoryRows(source, migrationRestore = false, sameDevice = true).isEmpty())
        assertTrue(migrationNoticeHistoryRows(source, migrationRestore = true, sameDevice = false).isEmpty())
        assertTrue(migrationNoticeHistoryRows(source, migrationRestore = false, sameDevice = false).isEmpty())
    }

    @Test fun absentFalseMalformedAndForeignEvidenceIsNoConnection() {
        val invalid = listOf(
            row("panel_assistant_authority", "string", "mqtt"),
            row("panel_assistant_authority", "string", ""),
            row("panel_assistant_authority", "string", "connected"),
            row("panel_assistant_authority", "string", "Native"),
            row("panel_assistant_authority", "long", "1"),
            row("panel_assistant_update_owner_seen_ms", "long", "0"),
            row("panel_assistant_update_owner_seen_ms", "long", "-1"),
            row("panel_assistant_update_owner_seen_ms", "long", "not-a-time"),
            row("panel_assistant_update_owner_seen_ms", "string", "1234"),
            row("migration_notice_connection_seen", "boolean", "0"),
            row("migration_notice_connection_seen", "boolean", "true"),
            row("migration_notice_connection_seen", "string", "1"),
            row("panel_assistant_authority", "string", "native", namespace = "other"),
            row("mqtt_connected", "boolean", "1"),
        )
        assertTrue(carry(emptyList()).isEmpty())
        // Broker-era rows among them still say where the store came from; none says Panel Assistant was seen.
        assertEquals(listOf("migration_notice_legacy_origin"), carry(invalid).map { it.key })
        val foreign = listOf(
            row("migration_notice_legacy_origin", "boolean", "0"),
            row("migration_notice_legacy_origin", "string", "1"),
            row("mqtt_broker", "string", "tcp://ha.local:1883", namespace = "other"),
        )
        assertTrue(carry(foreign).isEmpty())
    }

    private fun carry(rows: List<ConfigVault.StateRow>) =
        migrationNoticeHistoryRows(rows, migrationRestore = true, sameDevice = true)

    private fun row(
        key: String,
        type: String,
        value: String?,
        updatedAt: Long = 1L,
        namespace: String = "config",
    ) = ConfigVault.StateRow(namespace, key, type, value, updatedAt)

    private fun noticeRow(updatedAt: Long) =
        ConfigVault.StateRow("config", "migration_notice_connection_seen", "boolean", "1", updatedAt)

    private fun originRow(updatedAt: Long) =
        ConfigVault.StateRow("config", "migration_notice_legacy_origin", "boolean", "1", updatedAt)
}
