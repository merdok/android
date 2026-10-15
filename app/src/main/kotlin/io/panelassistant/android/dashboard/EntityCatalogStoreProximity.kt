package io.panelassistant.android.dashboard

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.panelassistant.android.dashboard.EntityCatalogStore.ProximityEpisodeRow
import io.panelassistant.android.dashboard.EntityCatalogStore.ProximityModelRow
import io.panelassistant.android.dashboard.EntityCatalogStore.ProximityRollupRow

private val PROXIMITY_FINGERPRINT = Regex("^[a-f0-9]{32,64}$")
private const val MAX_PROXIMITY_SNAPSHOT_BYTES = 64 * 1024
private const val MAX_PROXIMITY_MODELS = 2
private const val MAX_PROXIMITY_ROLLUPS = 5_000
private const val MAX_PROXIMITY_EPISODES = 512

fun EntityCatalogStore.readProximityModel(fingerprint: String): ProximityModelRow? {
    if (!PROXIMITY_FINGERPRINT.matches(fingerprint)) return null
    return readableDatabase.rawQuery(
        "SELECT algorithm_version,behavior_signature,snapshot_json,ready,updated_at FROM proximity_model WHERE fingerprint=?",
        arrayOf(fingerprint),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ProximityModelRow(
            fingerprint = fingerprint,
            algorithmVersion = cursor.getInt(0),
            behaviorSignature = cursor.getString(1),
            snapshotJson = cursor.getString(2),
            ready = cursor.getInt(3) != 0,
            updatedAt = cursor.getLong(4),
        )
    }
}

/** One bounded transaction owns model promotion and its coarse evidence. Raw events never enter SQLite. */
fun EntityCatalogStore.writeProximityBatch(
    model: ProximityModelRow,
    rollups: List<ProximityRollupRow>,
    episodes: List<ProximityEpisodeRow>,
    now: Long,
) {
    require(PROXIMITY_FINGERPRINT.matches(model.fingerprint))
    require(model.algorithmVersion in 1..10_000)
    require(model.behaviorSignature.length <= 500)
    require(model.snapshotJson.toByteArray(Charsets.UTF_8).size <= MAX_PROXIMITY_SNAPSHOT_BYTES)
    observedWrite("proximity-history") {
        val db = writableDatabase
        db.beginTransaction()
        try {
        // SQLite REPLACE deletes the parent before inserting it. With foreign keys enabled that
        // would cascade-delete all rollups and episodes at every model checkpoint, so use a
        // portable update-then-insert upsert instead of REPLACE/modern ON CONFLICT syntax.
        val modelValues = ContentValues(6).apply {
            put("algorithm_version", model.algorithmVersion)
            put("behavior_signature", model.behaviorSignature)
            put("snapshot_json", model.snapshotJson)
            put("ready", if (model.ready) 1 else 0)
            put("updated_at", model.updatedAt)
        }
        val updated = db.update(
            "proximity_model",
            modelValues,
            "fingerprint=?",
            arrayOf(model.fingerprint),
        )
        if (updated == 0) {
            modelValues.put("fingerprint", model.fingerprint)
            db.insertOrThrow("proximity_model", null, modelValues)
        }
        val mergeRollup = db.compileStatement(
            """UPDATE proximity_sample SET
               sample_count=MIN(1000000,sample_count+?),raw_min=MIN(raw_min,?),
               raw_max=MAX(raw_max,?),raw_sum=raw_sum+?,raw_sum_squares=raw_sum_squares+?,
               excursion_count=excursion_count+?,gesture_count=gesture_count+?
               WHERE fingerprint=? AND bucket=?""",
        )
        try {
            for (row in rollups) {
                if (row.fingerprint != model.fingerprint || row.sampleCount <= 0) continue
                mergeRollup.clearBindings()
                mergeRollup.bindLong(1, row.sampleCount.coerceAtMost(1_000_000).toLong())
                mergeRollup.bindDouble(2, row.rawMin)
                mergeRollup.bindDouble(3, row.rawMax)
                mergeRollup.bindDouble(4, row.rawSum)
                mergeRollup.bindDouble(5, row.rawSquareSum)
                mergeRollup.bindLong(6, row.excursionCount.coerceAtLeast(0).toLong())
                mergeRollup.bindLong(7, row.gestureCount.coerceAtLeast(0).toLong())
                mergeRollup.bindString(8, row.fingerprint)
                mergeRollup.bindLong(9, row.bucket)
                if (mergeRollup.executeUpdateDelete() == 0) {
                    db.execSQL(
                        """INSERT INTO proximity_sample(
                           fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_sum_squares,
                           excursion_count,gesture_count) VALUES(?,?,?,?,?,?,?,?,?)""",
                        arrayOf<Any?>(
                            row.fingerprint, row.bucket, row.sampleCount.coerceAtMost(1_000_000),
                            row.rawMin, row.rawMax, row.rawSum, row.rawSquareSum,
                            row.excursionCount.coerceAtLeast(0), row.gestureCount.coerceAtLeast(0),
                        ),
                    )
                }
            }
        } finally {
            mergeRollup.close()
        }
        for (row in episodes) {
            if (row.fingerprint != model.fingerprint) continue
            db.execSQL(
                """INSERT INTO proximity_episode(
                   fingerprint,started_at,duration_ms,peak_level,completed,guided)
                   VALUES(?,?,?,?,?,?)""",
                arrayOf<Any?>(
                    row.fingerprint, row.startedAt, row.durationMs.coerceIn(0, 60_000),
                    row.peakLevel.coerceIn(0, 100), if (row.completed) 1 else 0,
                    if (row.guided) 1 else 0,
                ),
            )
        }
        val cutoffBucket = proximityCutoffBucket(now)
        db.execSQL("DELETE FROM proximity_sample WHERE bucket<?", arrayOf(cutoffBucket))
        db.execSQL(
            """DELETE FROM proximity_sample WHERE rowid IN (
               SELECT rowid FROM proximity_sample ORDER BY bucket DESC LIMIT -1 OFFSET $MAX_PROXIMITY_ROLLUPS)""",
        )
        db.execSQL(
            """DELETE FROM proximity_episode WHERE fingerprint=? AND rowid NOT IN (
               SELECT rowid FROM proximity_episode WHERE fingerprint=? ORDER BY started_at DESC LIMIT $MAX_PROXIMITY_EPISODES)""",
            arrayOf(model.fingerprint, model.fingerprint),
        )
        val retainedFingerprints = db.rawQuery(
            "SELECT fingerprint FROM proximity_model ORDER BY updated_at DESC LIMIT $MAX_PROXIMITY_MODELS",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (retainedFingerprints.isNotEmpty()) {
            val placeholders = retainedFingerprints.joinToString(",") { "?" }
            val args = retainedFingerprints.toTypedArray()
            db.delete("proximity_model", "fingerprint NOT IN ($placeholders)", args)
            db.delete("proximity_sample", "fingerprint NOT IN ($placeholders)", args)
            db.delete("proximity_episode", "fingerprint NOT IN ($placeholders)", args)
        }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    observedMaintenance(now)
}

/** Every ready calibration, for the backup archive. Unready rows hold nothing worth restoring. */
fun EntityCatalogStore.exportProximityModels(): List<ProximityModelRow> = readableDatabase.rawQuery(
    "SELECT fingerprint,algorithm_version,behavior_signature,snapshot_json,ready,updated_at FROM proximity_model " +
        "WHERE ready=1 ORDER BY updated_at DESC LIMIT $MAX_PROXIMITY_MODELS",
    null,
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(
            ProximityModelRow(
                fingerprint = cursor.getString(0),
                algorithmVersion = cursor.getInt(1),
                behaviorSignature = cursor.getString(2),
                snapshotJson = cursor.getString(3),
                ready = cursor.getInt(4) != 0,
                updatedAt = cursor.getLong(5),
            ),
        )
    }
}

/**
 * Adds a calibration carried from elsewhere (a backup, or the row saved under an older key) only when
 * no row holds its fingerprint, so a calibration made on this install is never replaced. It touches
 * the model table alone: no pruning, so it can evict nothing.
 */
fun EntityCatalogStore.insertProximityModelIfAbsent(model: ProximityModelRow): Boolean = observedWrite("proximity-history") {
    require(PROXIMITY_FINGERPRINT.matches(model.fingerprint))
    require(model.behaviorSignature.length <= 500)
    require(model.snapshotJson.toByteArray(Charsets.UTF_8).size <= MAX_PROXIMITY_SNAPSHOT_BYTES)
    val values = ContentValues(6).apply {
        put("fingerprint", model.fingerprint)
        put("algorithm_version", model.algorithmVersion)
        put("behavior_signature", model.behaviorSignature)
        put("snapshot_json", model.snapshotJson)
        put("ready", if (model.ready) 1 else 0)
        put("updated_at", model.updatedAt)
    }
    writableDatabase.insertWithOnConflict("proximity_model", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
}

fun EntityCatalogStore.clearProximityLearning(fingerprint: String): Unit = observedWrite("proximity-history-reset") {
    if (!PROXIMITY_FINGERPRINT.matches(fingerprint)) return
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.delete("proximity_episode", "fingerprint=?", arrayOf(fingerprint))
        db.delete("proximity_sample", "fingerprint=?", arrayOf(fingerprint))
        db.delete("proximity_model", "fingerprint=?", arrayOf(fingerprint))
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
