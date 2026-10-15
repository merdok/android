package io.panelassistant.android.dashboard

import android.database.sqlite.SQLiteDatabase
import io.panelassistant.android.control.AmbientHistoryMinute
import io.panelassistant.android.control.AmbientMinuteAggregate
import io.panelassistant.android.dashboard.EntityCatalogStore.Companion.MINUTE_MS

// Two inclusive seven-day minute streams (20,162 rows) plus 3,838 rows of headroom.
private const val AMBIENT_GLOBAL_ROW_LIMIT = 24_000
private const val PANEL_AMBIENT_SOURCE_ID = "panel"

/** Batched IO-side sink for ambient evidence; sensor callbacks only touch the RAM accumulator. */
internal fun EntityCatalogStore.recordAmbientHistory(
    samples: List<AmbientMinuteAggregate>,
    nowMs: Long,
    activeContextId: String = samples.lastOrNull()?.key?.contextId.orEmpty(),
    activeSourceId: String = samples.lastOrNull()?.key?.sourceId.orEmpty(),
): Unit = observedWrite("ambient-history") {
    if (samples.isEmpty() || nowMs < 0L) return
    val nowMinute = nowMs / MINUTE_MS
    val oldestMinute = ambientOldestMinute(nowMinute)
    val db = writableDatabase
    db.beginTransaction()
    try {
        val statement = db.compileStatement(
            """UPDATE ambient_lux_minute SET lux_integral=lux_integral+?,
               coverage_ms=coverage_ms+?,min_lux=min(min_lux,?),max_lux=max(max_lux,?),
               last_lux=?,sample_count=sample_count+?,
               baseline_log_integral=baseline_log_integral+?,
               baseline_coverage_ms=baseline_coverage_ms+?
               WHERE context_id=? AND source_id=? AND minute=?""",
        )
        try {
            samples.asSequence()
                .filter { it.key.contextId.length in 1..128 && it.key.sourceId.length in 1..255 }
                .filter { it.key.minute in oldestMinute..nowMinute }
                .filter { it.coverageMs in 1..MINUTE_MS && it.sampleCount > 0 }
                .filter { it.luxIntegral.isFinite() && it.minLux.isFinite() && it.maxLux.isFinite() && it.lastLux.isFinite() }
                .forEach { sample ->
                    db.execSQL(
                        """INSERT OR IGNORE INTO ambient_lux_minute(
                           context_id,source_id,minute,min_lux,max_lux,last_lux)
                           VALUES(?,?,?,?,?,?)""",
                        arrayOf<Any?>(sample.key.contextId, sample.key.sourceId, sample.key.minute,
                            sample.minLux, sample.maxLux, sample.lastLux),
                    )
                    statement.clearBindings()
                    statement.bindDouble(1, sample.luxIntegral)
                    statement.bindLong(2, sample.coverageMs)
                    statement.bindDouble(3, sample.minLux)
                    statement.bindDouble(4, sample.maxLux)
                    statement.bindDouble(5, sample.lastLux)
                    statement.bindLong(6, sample.sampleCount)
                    statement.bindDouble(7, sample.baselineLogIntegral)
                    statement.bindLong(8, sample.baselineCoverageMs)
                    statement.bindString(9, sample.key.contextId)
                    statement.bindString(10, sample.key.sourceId)
                    statement.bindLong(11, sample.key.minute)
                    statement.executeUpdateDelete()
                }
        } finally { statement.close() }
        pruneAmbientHistory(db, oldestMinute, nowMinute, activeContextId, activeSourceId)
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

/** Idempotent bootstrap sink. Existing live rows win wholesale over imported HA history. */
internal fun EntityCatalogStore.seedAmbientHistory(samples: List<AmbientMinuteAggregate>, nowMs: Long): Unit =
    observedWrite("ambient-history-seed") {
    if (samples.isEmpty() || nowMs < 0L) return
    val nowMinute = nowMs / MINUTE_MS
    val oldestMinute = ambientOldestMinute(nowMinute)
    val db = writableDatabase
    db.beginTransaction()
    try {
        val statement = db.compileStatement(
            """INSERT OR IGNORE INTO ambient_lux_minute(
               context_id,source_id,minute,lux_integral,coverage_ms,min_lux,max_lux,last_lux,
               sample_count,baseline_log_integral,baseline_coverage_ms)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
        )
        try {
            samples.asSequence()
                .filter { it.key.contextId.length in 1..128 && it.key.sourceId.length in 1..255 }
                .filter { it.key.minute in oldestMinute..nowMinute }
                .filter { it.coverageMs in 1..MINUTE_MS && it.sampleCount > 0 }
                .filter { it.baselineCoverageMs in 0..it.coverageMs }
                .filter {
                    it.luxIntegral.isFinite() && it.luxIntegral >= 0.0 &&
                        it.minLux.isFinite() && it.minLux >= 0.0 &&
                        it.maxLux.isFinite() && it.maxLux >= it.minLux &&
                        it.lastLux.isFinite() && it.lastLux >= 0.0 &&
                        it.baselineLogIntegral.isFinite() && it.baselineLogIntegral >= 0.0
                }
                .forEach { sample ->
                    statement.clearBindings()
                    statement.bindString(1, sample.key.contextId)
                    statement.bindString(2, sample.key.sourceId)
                    statement.bindLong(3, sample.key.minute)
                    statement.bindDouble(4, sample.luxIntegral)
                    statement.bindLong(5, sample.coverageMs)
                    statement.bindDouble(6, sample.minLux)
                    statement.bindDouble(7, sample.maxLux)
                    statement.bindDouble(8, sample.lastLux)
                    statement.bindLong(9, sample.sampleCount)
                    statement.bindDouble(10, sample.baselineLogIntegral)
                    statement.bindLong(11, sample.baselineCoverageMs)
                    statement.executeInsert()
                }
        } finally { statement.close() }
        val active = samples.last().key
        pruneAmbientHistory(db, oldestMinute, nowMinute, active.contextId, active.sourceId)
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

internal fun EntityCatalogStore.ambientHistory(
    contextId: String,
    sourceId: String,
    sinceMinute: Long,
    untilMinute: Long = Long.MAX_VALUE,
): List<AmbientHistoryMinute> = readableDatabase.rawQuery(
    """SELECT minute,lux_integral,coverage_ms,min_lux,max_lux,last_lux,sample_count,
       baseline_log_integral,baseline_coverage_ms FROM ambient_lux_minute
       WHERE context_id=? AND source_id=? AND minute>=? AND minute<=? ORDER BY minute""",
    arrayOf(contextId, sourceId, sinceMinute.toString(), untilMinute.toString()),
).use { cursor -> buildList {
    while (cursor.moveToNext()) add(AmbientHistoryMinute(
        key = io.panelassistant.android.control.AmbientHistoryKey(contextId, sourceId, cursor.getLong(0)),
        luxIntegral = cursor.getDouble(1), coverageMs = cursor.getLong(2),
        minLux = cursor.getDouble(3), maxLux = cursor.getDouble(4), lastLux = cursor.getDouble(5),
        sampleCount = cursor.getLong(6), baselineLogIntegral = cursor.getDouble(7),
        baselineCoverageMs = cursor.getLong(8),
    ))
} }

/** Retain the complete active seven-day partition first and every irreplaceable panel-sensor
 * minute second, then spend the fixed remainder on recoverable inactive HA evidence. */
private fun pruneAmbientHistory(
    db: SQLiteDatabase,
    oldestMinute: Long,
    nowMinute: Long,
    activeContextId: String,
    activeSourceId: String,
) {
    db.execSQL("DELETE FROM ambient_lux_minute WHERE minute<? OR minute>?", arrayOf(oldestMinute, nowMinute))
    db.execSQL(
        """DELETE FROM ambient_lux_minute WHERE rowid IN (
           SELECT rowid FROM ambient_lux_minute
           ORDER BY CASE
                      WHEN context_id=? AND source_id=? THEN 0
                      WHEN source_id=? THEN 1
                      ELSE 2
                    END,
                    minute DESC,context_id,source_id
           LIMIT -1 OFFSET $AMBIENT_GLOBAL_ROW_LIMIT)""",
        arrayOf(activeContextId, activeSourceId, PANEL_AMBIENT_SOURCE_ID),
    )
}

internal fun EntityCatalogStore.resetAmbientHistory(contextId: String? = null, sourceId: String? = null): Int =
    observedWrite("ambient-history-reset") {
        when {
            contextId == null && sourceId == null -> writableDatabase.delete("ambient_lux_minute", null, null)
            contextId != null && sourceId == null -> writableDatabase.delete("ambient_lux_minute", "context_id=?", arrayOf(contextId))
            contextId != null -> writableDatabase.delete("ambient_lux_minute", "context_id=? AND source_id=?", arrayOf(contextId, sourceId!!))
            else -> 0
        }
    }
