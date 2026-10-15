package io.panelassistant.android.dashboard

import io.panelassistant.android.dashboard.EntityCatalogStore.Companion.MINUTE_MS
import io.panelassistant.android.metrics.DashboardMetrics
import io.panelassistant.android.metrics.MetricPayload

internal fun EntityCatalogStore.recordDashboardPerformance(samples: List<DashboardPerformanceAggregate>): Unit =
    observedWrite("dashboard-performance-history") {
    if (samples.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
        samples.forEach { sample ->
            val key = sample.key
            // The accumulation the 25-column UPDATE expressed in SQL now happens against the decoded
            // bucket, under each metric's declared rule. Same transaction, so the read-merge-write is
            // as atomic as the upsert it replaces.
            val existing = db.rawQuery(
                "SELECT payload FROM dashboard_metric_minute WHERE instance=? AND path=? AND minute=?",
                arrayOf(key.instance, key.path, key.minute.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) MetricPayload.decode(cursor.getBlob(0)) else null
            } ?: emptyMap() // a corrupt bucket is rebuilt rather than propagated

            val merged = DashboardMetrics.merge(
                existing,
                DashboardMetricCodec.values(sample.filterActive, sample.entityCount, sample.totals),
            )
            db.execSQL(
                "INSERT OR REPLACE INTO dashboard_metric_minute(instance,path,minute,payload) VALUES(?,?,?,?)",
                arrayOf<Any?>(key.instance, key.path, key.minute, MetricPayload.encode(merged)),
            )
        }
        val latestMinute = samples.maxOf { it.key.minute }
        if (performanceMaintenanceGate.admit(latestMinute * MINUTE_MS)) {
            db.execSQL(
                "DELETE FROM dashboard_metric_minute WHERE minute<?",
                arrayOf(performanceCutoffMinute(latestMinute)),
            )
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun EntityCatalogStore.dashboardPerformanceHistory(
    instance: String,
    path: String,
    sinceMinute: Long,
): List<DashboardPerformanceMinute> = readableDatabase.rawQuery(
    """SELECT minute,payload FROM dashboard_metric_minute
       WHERE instance=? AND path=? AND minute>=? ORDER BY minute""",
    arrayOf(instance, path, sinceMinute.toString()),
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) {
            // A bucket that fails to decode is skipped rather than reported as zeroes: an absent
            // point in a diagnostic chart is honest, a fabricated one is not.
            MetricPayload.decode(cursor.getBlob(1))?.let { values ->
                add(DashboardMetricCodec.minute(cursor.getLong(0), values))
            }
        }
    }
}
