package io.panelassistant.android.persistence

import android.content.Context
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.dashboard.readThenClose
import io.panelassistant.android.sensors.ProximityCalibrationRuntime
import io.panelassistant.android.util.Json
import org.json.JSONObject

/**
 * The saved proximity calibration, carried in the backup manifest so a reinstall and restore of the same
 * panel keeps it.
 *
 * It lives in its own table, not `app_state`, so the state dump never held it. The section is inline in the
 * manifest rather than a new archive entry, because a build that predates it refuses any undeclared entry
 * but ignores an unknown manifest member. Each row keeps its store key, which hashes the sensor route and
 * firmware: on a panel whose readings differ the key never matches and the row is simply not used.
 */
/** Where a backup reads the saved calibrations and a restore writes them back. */
internal interface ProximityCalibrationCustody {
    fun export(): List<EntityCatalogStore.ProximityModelRow>

    /** Writes each row whose key holds nothing yet, and returns how many it wrote. */
    fun restore(rows: List<EntityCatalogStore.ProximityModelRow>): Int
}

/** The catalog's model table, opened per call like the backup's other reads. */
internal class CatalogProximityCalibrationCustody(private val context: Context) : ProximityCalibrationCustody {
    override fun export() = readThenClose(EntityCatalogStore(context), { it.close() }) { it.exportProximityModels() }

    override fun restore(rows: List<EntityCatalogStore.ProximityModelRow>) =
        readThenClose(EntityCatalogStore(context), { it.close() }) { store -> rows.count(store::insertProximityModelIfAbsent) }
}

internal object ProximityCalibrationBackup {
    const val SECTION_KEY = "proximity_calibration"
    private const val MAX_ROWS = 2
    private val FINGERPRINT_RE = Regex("[a-f0-9]{32,64}")

    /** The manifest member (comma-prefixed), or empty when nothing is calibrated. */
    fun manifestFragment(rows: List<EntityCatalogStore.ProximityModelRow>): String {
        val carried = rows.filter { it.ready }.take(MAX_ROWS)
        if (carried.isEmpty()) return ""
        return ",\"$SECTION_KEY\":[" + carried.joinToString(",") { row ->
            "{\"fingerprint\":${Json.str(row.fingerprint)},\"algorithm_version\":${row.algorithmVersion}," +
                "\"behavior_signature\":${Json.str(row.behaviorSignature)},\"snapshot\":${Json.str(row.snapshotJson)}," +
                "\"updated_at\":${row.updatedAt}}"
        } + "]"
    }

    /**
     * The rows a restore may write: none unless [samePanel], the proof every device-local value needs, and
     * only well-formed ready calibrations the runtime itself would load. A malformed row is dropped alone.
     */
    fun restorable(manifest: JSONObject, samePanel: Boolean): List<EntityCatalogStore.ProximityModelRow> {
        if (!samePanel) return emptyList()
        val rows = manifest.optJSONArray(SECTION_KEY) ?: return emptyList()
        return (0 until minOf(rows.length(), MAX_ROWS)).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                val version = row.getInt("algorithm_version")
                val snapshot = row.getString("snapshot")
                val calibration = requireNotNull(ProximityCalibrationRuntime.decode(snapshot))
                require(
                    version == ProximityCalibrationRuntime.STORAGE_VERSION ||
                        (version == ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION && calibration.version == 1),
                )
                EntityCatalogStore.ProximityModelRow(
                    fingerprint = row.getString("fingerprint").also { require(FINGERPRINT_RE.matches(it)) },
                    algorithmVersion = version,
                    behaviorSignature = row.getString("behavior_signature").also { require(it.length <= 500) },
                    snapshotJson = snapshot,
                    ready = true,
                    updatedAt = row.getLong("updated_at"),
                )
            }.getOrNull()
        }
    }
}
