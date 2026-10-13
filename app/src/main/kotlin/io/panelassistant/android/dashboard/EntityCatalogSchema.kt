package io.panelassistant.android.dashboard

import android.database.sqlite.SQLiteDatabase
import io.panelassistant.android.metrics.DashboardMetrics
import io.panelassistant.android.metrics.MetricPayload

/** Sequential forward-only schema contract. Never add an ad-hoc conditional to [onUpgrade]. */
object EntityCatalogSchema {
    const val CURRENT_VERSION = 14

    /**
     * Oldest on-disk structure this build can carry forward — the schema public v0.9.5 shipped.
     *
     * Steps below this are deliberately deleted rather than kept forever: each one is dead weight that
     * still has to be read, tested and reasoned about, and none can run because no supported release
     * produces such a database. Anything older is refused before the database is opened; it must never
     * reach [plan], because a throw inside `onUpgrade` aborts the open and takes configuration down.
     *
     * Raising this floor is a compatibility decision, not a cleanup: it makes upgrading from an older
     * release impossible, so it belongs with a release that states the supported upgrade range.
     */
    const val MINIMUM_SUPPORTED_VERSION = 11

    /** Candidate metadata and runtime admission share this one finite compatibility boundary. */
    internal val DATABASE_COMPATIBILITY = DatabaseCompatibilityBoundary(
        formatVersion = DatabaseCompatibilityBoundary.FORMAT_VERSION,
        databaseName = DatabaseCompatibilityBoundary.DATABASE_NAME,
        minimumSchema = MINIMUM_SUPPORTED_VERSION,
        maximumSchema = CURRENT_VERSION,
    )

    /**
     * Per-minute dashboard measurements, with the metric *set* held as data in [payload].
     *
     * Replaces the 25 fixed columns of `dashboard_performance`, where the metric list was part of the
     * schema and each new probe therefore cost a migration, a version bump and a fleet release —
     * which in practice meant the instrumentation was never added when it was wanted.
     *
     * Storage is a wash, not a saving. On a real panel the payloads are 46 bytes per bucket (207,891
     * total), but the `(instance, path)` key costs ~59 more, so the table lands at 475,136 against
     * 450,560 for the columns it replaces — about 5% more on one table, 0.2% of the database. The
     * prototype that measured 0.66x keyed rows on an interned integer scope id; interning here would
     * need a second table and a non-additive key change, which is a poor trade for bytes that do not
     * matter. The flexibility is the point; the storage merely has to not regress meaningfully.
     */
    internal val ENTITY_TABLE_SQL = """CREATE TABLE entity(
            instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT '',
            attributes_json TEXT NOT NULL DEFAULT '{}', metadata_json TEXT NOT NULL DEFAULT '{}',
            first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, missing_streak INTEGER NOT NULL DEFAULT 0,
            tombstone_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,entity_id))"""

    internal val DASHBOARD_TABLE_SQL = """CREATE TABLE dashboard(
            instance TEXT NOT NULL, path TEXT NOT NULL, config_hash TEXT NOT NULL DEFAULT '',
            config_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'disabled',
            last_sync_at INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
            unresolved_json TEXT NOT NULL DEFAULT '[]', sync_generation INTEGER NOT NULL DEFAULT 0,
            issues_json TEXT NOT NULL DEFAULT '[]', analyzer_policy_version INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path))"""

    internal val DASHBOARD_ENTITY_TABLE_SQL = """CREATE TABLE dashboard_entity(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL,
            referenced_by_config INTEGER NOT NULL DEFAULT 0, referenced_at_runtime INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0,
            reasons TEXT NOT NULL DEFAULT '', first_access_at INTEGER NOT NULL DEFAULT 0,
            last_access_at INTEGER NOT NULL DEFAULT 0, access_count INTEGER NOT NULL DEFAULT 0,
            update_count INTEGER NOT NULL DEFAULT 0, update_bytes INTEGER NOT NULL DEFAULT 0,
            rate_window_started_at INTEGER NOT NULL DEFAULT 0, rate_update_bytes INTEGER NOT NULL DEFAULT 0,
            last_update_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id))"""

    internal val DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL = """CREATE TABLE dashboard_entity_traffic_minute(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
            update_bytes INTEGER NOT NULL DEFAULT 0, span_started_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id,minute))"""

    internal val DASHBOARD_IGNORED_ISSUE_TABLE_SQL = """CREATE TABLE dashboard_ignored_issue(
            instance TEXT NOT NULL, path TEXT NOT NULL, fingerprint TEXT NOT NULL, ignored_at INTEGER NOT NULL,
            PRIMARY KEY(instance,path,fingerprint),
            FOREIGN KEY(instance,path) REFERENCES dashboard(instance,path) ON DELETE CASCADE)"""

    internal val DASHBOARD_METRIC_TABLE_SQL = """CREATE TABLE dashboard_metric_minute(
            instance TEXT NOT NULL, path TEXT NOT NULL, minute INTEGER NOT NULL,
            payload BLOB NOT NULL,
            PRIMARY KEY(instance,path,minute)) WITHOUT ROWID"""

    /**
     * Moves existing history into payloads. Runs inside the migration transaction, so a failure
     * leaves the database as it was rather than half-converted.
     *
     * The source rows are deleted here; the table itself is dropped by the following step, which
     * declares the compatibility break that its renames require anyway.
     */
    internal fun migrateDashboardPerformanceToPayloads(db: SQLiteDatabase) {
        val columns = DashboardMetrics.METRICS.joinToString(",") { it.name }
        db.rawQuery(
            "SELECT instance,path,minute,$columns FROM dashboard_performance", emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val values = DashboardMetrics.METRICS.withIndex().associate { (index, metric) ->
                    metric.id to cursor.getLong(3 + index)
                }
                db.execSQL(
                    "INSERT OR REPLACE INTO dashboard_metric_minute(instance,path,minute,payload) VALUES(?,?,?,?)",
                    arrayOf<Any?>(
                        cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                        MetricPayload.encode(values),
                    ),
                )
            }
        }
        db.execSQL("DELETE FROM dashboard_performance")
    }

    internal val PERFORMANCE_HISTORY_TABLE_SQL = """CREATE TABLE dashboard_performance(
            instance TEXT NOT NULL,path TEXT NOT NULL,minute INTEGER NOT NULL,
            filter_active INTEGER NOT NULL DEFAULT 0,entity_count INTEGER NOT NULL DEFAULT 0,
            sample_ms INTEGER NOT NULL DEFAULT 0,frames INTEGER NOT NULL DEFAULT 0,
            payload_bytes INTEGER NOT NULL DEFAULT 0,updates INTEGER NOT NULL DEFAULT 0,
            hydration_updates INTEGER NOT NULL DEFAULT 0,observer_micros INTEGER NOT NULL DEFAULT 0,
            dropped_frames INTEGER NOT NULL DEFAULT 0,state_task_micros INTEGER NOT NULL DEFAULT 0,
            state_task_max_micros INTEGER NOT NULL DEFAULT 0,interaction_count INTEGER NOT NULL DEFAULT 0,
            interaction_max_micros INTEGER NOT NULL DEFAULT 0,input_delay_micros INTEGER NOT NULL DEFAULT 0,
            interaction_processing_micros INTEGER NOT NULL DEFAULT 0,presentation_micros INTEGER NOT NULL DEFAULT 0,
            loaf_count INTEGER NOT NULL DEFAULT 0,blocking_micros INTEGER NOT NULL DEFAULT 0,
            loaf_max_micros INTEGER NOT NULL DEFAULT 0,script_micros INTEGER NOT NULL DEFAULT 0,
            render_micros INTEGER NOT NULL DEFAULT 0,long_task_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,minute))"""

    internal val APP_STATE_REVISION_TABLE_SQL = """CREATE TABLE app_state_revision(
            revision INTEGER PRIMARY KEY AUTOINCREMENT,
            committed_at INTEGER NOT NULL,
            namespace TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'app')"""

    internal val APP_STATE_NAMESPACE_TABLE_SQL = """CREATE TABLE app_state_namespace(
            namespace TEXT PRIMARY KEY,
            imported_at INTEGER NOT NULL,
            legacy_name TEXT NOT NULL DEFAULT '')"""

    internal val APP_STATE_TABLE_SQL = """CREATE TABLE app_state(
            namespace TEXT NOT NULL,
            state_key TEXT NOT NULL,
            value_type TEXT NOT NULL,
            value_text TEXT,
            updated_at INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            PRIMARY KEY(namespace,state_key),
            FOREIGN KEY(revision) REFERENCES app_state_revision(revision))"""

    internal val PROXIMITY_MODEL_TABLE_SQL = """CREATE TABLE proximity_model(
            fingerprint TEXT PRIMARY KEY,
            algorithm_version INTEGER NOT NULL,
            behavior_signature TEXT NOT NULL DEFAULT '',
            snapshot_json TEXT NOT NULL,
            ready INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL)"""

    internal val PROXIMITY_SAMPLE_TABLE_SQL = """CREATE TABLE proximity_sample(
            fingerprint TEXT NOT NULL,
            bucket INTEGER NOT NULL,
            sample_count INTEGER NOT NULL,
            raw_min REAL NOT NULL,
            raw_max REAL NOT NULL,
            raw_sum REAL NOT NULL,
            raw_sum_squares REAL NOT NULL,
            excursion_count INTEGER NOT NULL DEFAULT 0,
            gesture_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(fingerprint,bucket),
            FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)"""

    internal val PROXIMITY_EPISODE_TABLE_SQL = """CREATE TABLE proximity_episode(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fingerprint TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            peak_level INTEGER NOT NULL,
            completed INTEGER NOT NULL,
            guided INTEGER NOT NULL,
            FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)"""

    internal val AMBIENT_HISTORY_TABLE_SQL = """CREATE TABLE ambient_lux_minute(
            context_id TEXT NOT NULL,source_id TEXT NOT NULL,minute INTEGER NOT NULL,
            lux_integral REAL NOT NULL DEFAULT 0,coverage_ms INTEGER NOT NULL DEFAULT 0,
            min_lux REAL NOT NULL DEFAULT 0,max_lux REAL NOT NULL DEFAULT 0,last_lux REAL NOT NULL DEFAULT 0,
            sample_count INTEGER NOT NULL DEFAULT 0,baseline_log_integral REAL NOT NULL DEFAULT 0,
            baseline_coverage_ms INTEGER NOT NULL DEFAULT 0,
             PRIMARY KEY(context_id,source_id,minute))"""

    /**
     * Rebuilds every table whose name or columns changed, copying rows across.
     *
     * A rebuild rather than `ALTER TABLE ... RENAME COLUMN` because that needs SQLite 3.25, which
     * arrived in API 30, and `minSdk` is 26 — an API 27 panel cannot execute it. Running once at
     * upgrade, on a few thousand rows, so the copy costs nothing worth optimising.
     *
     * Order is the delicate part. `dashboard` is a foreign-key parent and `dashboard_issue_ignore`
     * cascades from it, so dropping `dashboard` while that child exists would silently delete the
     * user's ignored issues. `PRAGMA foreign_keys` is a no-op inside a transaction and `onUpgrade`
     * already holds one, so the constraint cannot simply be switched off: instead the child is read
     * out and dropped first, the parent is rebuilt, and the child is recreated and refilled.
     */
    internal fun rebuildTablesWithFinalNames(db: SQLiteDatabase) {
        // 1. Take the cascading child out of the way, keeping its rows.
        val ignoredIssues = db.rawQuery(
            "SELECT instance,path,fingerprint,ignored_at FROM dashboard_issue_ignore", emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(arrayOf<Any?>(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
                }
            }
        }
        db.execSQL("DROP TABLE dashboard_issue_ignore")

        // 2. Rebuild the parent, then the remaining tables. Each is create-copy-drop-rename.
        rebuild(
            db, "dashboard", DASHBOARD_TABLE_SQL,
            "instance,path,config_hash,config_json,status,last_sync_at,error,unresolved_json," +
                "sync_generation,issues_json,analyzer_policy_version",
            "instance,path,config_hash,config_json,status,last_sync,error,unresolved_json," +
                "sync_generation,issues_json,analyzer_policy_version",
        )
        rebuild(
            db, "entity", ENTITY_TABLE_SQL,
            "instance,entity_id,state,attributes_json,metadata_json,first_seen_at,last_seen_at," +
                "missing_streak,tombstone_at",
            "instance,entity_id,state,attributes_json,metadata_json,first_seen,last_seen," +
                "missing_streak,tombstone_at",
        )
        rebuild(
            db, "membership", DASHBOARD_ENTITY_TABLE_SQL,
            "instance,path,entity_id,referenced_by_config,referenced_at_runtime,pinned,excluded,reasons," +
                "first_access_at,last_access_at,access_count,update_count,update_bytes," +
                "rate_window_started_at,rate_update_bytes,last_update_at",
            "instance,path,entity_id,static_ref,runtime_ref,pinned,excluded,reasons," +
                "first_access,last_access,access_count,update_count,update_bytes," +
                "rate_window_start,rate_update_bytes,last_update_at",
            target = "dashboard_entity",
        )
        rebuild(
            db, "minute_rollup", DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL,
            "instance,path,entity_id,minute,access_count,update_count,update_bytes,span_started_at",
            "instance,path,entity_id,minute,access_count,update_count,update_bytes,span_start",
            target = "dashboard_entity_traffic_minute",
        )
        rebuild(
            db, "proximity_rollup", PROXIMITY_SAMPLE_TABLE_SQL,
            "fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_sum_squares,excursion_count,gesture_count",
            "fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_square_sum,excursion_count,gesture_count",
            target = "proximity_sample",
        )

        // 3. Recreate the child against the rebuilt parent and put its rows back.
        db.execSQL(DASHBOARD_IGNORED_ISSUE_TABLE_SQL)
        ignoredIssues.forEach { row ->
            db.execSQL(
                "INSERT OR IGNORE INTO dashboard_ignored_issue(instance,path,fingerprint,ignored_at) " +
                    "VALUES(?,?,?,?)",
                row,
            )
        }

        // These tables were not rebuilt, so their old indexes still exist.
        db.execSQL("DROP INDEX IF EXISTS app_state_updated")
        db.execSQL("DROP INDEX IF EXISTS proximity_episode_age")
        db.execSQL("DROP INDEX IF EXISTS ambient_lux_minute_age")
        listOf(
            "CREATE INDEX ix_entity_missing ON entity(instance,missing_streak)",
            "CREATE INDEX ix_dashboard_entity_load ON dashboard_entity(instance,path,update_bytes DESC)",
            "CREATE INDEX ix_dashboard_entity_traffic_minute_age ON dashboard_entity_traffic_minute(instance,path,minute)",
            "CREATE INDEX ix_proximity_sample_age ON proximity_sample(bucket)",
            "CREATE INDEX ix_app_state_updated ON app_state(namespace,updated_at)",
            "CREATE INDEX ix_proximity_episode_age ON proximity_episode(started_at)",
            "CREATE INDEX ix_ambient_lux_minute_age ON ambient_lux_minute(minute)",
        ).forEach(db::execSQL)
    }

    /**
     * Creates [createSql]'s table, copies [source] columns into [destination] columns positionally,
     * then drops [from]. [target] defaults to [from] for a rebuild that keeps its name.
     */
    private fun rebuild(
        db: SQLiteDatabase,
        from: String,
        createSql: String,
        destination: String,
        source: String,
        target: String = from,
    ) {
        val staging = "${target}_rebuild"
        db.execSQL(createSql.replaceFirst("CREATE TABLE $target(", "CREATE TABLE $staging("))
        db.execSQL("INSERT INTO $staging($destination) SELECT $source FROM $from")
        db.execSQL("DROP TABLE $from")
        db.execSQL("ALTER TABLE $staging RENAME TO $target")
    }

    /** Creates every table and index of the current schema on an empty database, in this order. */
    internal fun createTables(db: SQLiteDatabase) {
    db.execSQL(ENTITY_TABLE_SQL)
    db.execSQL(DASHBOARD_TABLE_SQL)
    db.execSQL(DASHBOARD_ENTITY_TABLE_SQL)
    db.execSQL(DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL)
    db.execSQL("CREATE INDEX ix_entity_missing ON entity(instance,missing_streak)")
    db.execSQL("CREATE INDEX ix_dashboard_entity_load ON dashboard_entity(instance,path,update_bytes DESC)")
    db.execSQL("CREATE INDEX ix_dashboard_entity_traffic_minute_age ON dashboard_entity_traffic_minute(instance,path,minute)")
    db.execSQL(DASHBOARD_IGNORED_ISSUE_TABLE_SQL)
    db.execSQL(DASHBOARD_METRIC_TABLE_SQL)
    db.execSQL("CREATE INDEX ix_dashboard_metric_minute_age ON dashboard_metric_minute(minute)")
    db.execSQL(APP_STATE_REVISION_TABLE_SQL)
    db.execSQL(APP_STATE_NAMESPACE_TABLE_SQL)
    db.execSQL(APP_STATE_TABLE_SQL)
    db.execSQL("CREATE INDEX ix_app_state_updated ON app_state(namespace,updated_at)")
    db.execSQL(PROXIMITY_MODEL_TABLE_SQL)
    db.execSQL(PROXIMITY_SAMPLE_TABLE_SQL)
    db.execSQL("CREATE INDEX ix_proximity_sample_age ON proximity_sample(bucket)")
    db.execSQL(PROXIMITY_EPISODE_TABLE_SQL)
    db.execSQL("CREATE INDEX ix_proximity_episode_age ON proximity_episode(started_at)")
    db.execSQL(AMBIENT_HISTORY_TABLE_SQL)
    db.execSQL("CREATE INDEX ix_ambient_lux_minute_age ON ambient_lux_minute(minute)")
    }

    /**
     * [transform] carries data that SQL alone cannot express — an encoding, for instance. It runs after
     * [sql], inside the same transaction, so a failure leaves the step as if it had never run. Prefer
     * plain [sql]; this exists so such a migration stays a declared step rather than becoming the ad-hoc
     * conditional in `onUpgrade` that this contract forbids.
     */
    data class Step(
        val from: Int,
        val to: Int,
        val sql: List<String>,
        val transform: ((SQLiteDatabase) -> Unit)? = null,
        /**
         * Declares that this step drops, renames or retypes something an older build reads.
         *
         * Such a change is allowed — the forward migration chain handles it on upgrade, which is the
         * common direction — but it cannot be *silent*, because the one thing the chain cannot do is run
         * backwards. The finite compatibility boundary ensures an older candidate never assumes that
         * a structure written by a future build is additive.
         */
        val breaksCompatibility: Boolean = false,
    )

    private val steps = mapOf(
        11 to Step(
            11, 12,
            listOf("ALTER TABLE dashboard ADD COLUMN analyzer_policy_version INTEGER NOT NULL DEFAULT 0"),
        ),
        12 to Step(
            12, 13,
            listOf(
                DASHBOARD_METRIC_TABLE_SQL,
                "CREATE INDEX ix_dashboard_metric_minute_age ON dashboard_metric_minute(minute)",
            ),
            // Carries existing history into payloads. Additive: dashboard_performance is left in place so
            // an older build still opens this database; only its rows move, costing such a build recent
            // diagnostic history rather than anything it cannot rebuild.
            transform = ::migrateDashboardPerformanceToPayloads,
        ),
        13 to Step(
            13, 14,
            listOf(
                // Superseded by dashboard_metric_minute and already emptied by the previous step.
                "DROP TABLE IF EXISTS dashboard_performance",
            ),
            // Renaming a column needs SQLite 3.25 (API 30) and minSdk is 26, so every table whose
            // columns change is rebuilt and copied instead. Ordered so `dashboard` -- a foreign-key
            // parent with ON DELETE CASCADE -- is never dropped while a child still references it:
            // DROP TABLE fires that cascade, and PRAGMA foreign_keys cannot be turned off inside the
            // transaction onUpgrade already holds.
            transform = ::rebuildTablesWithFinalNames,
            breaksCompatibility = true,
        ),
    )

    fun plan(oldVersion: Int, newVersion: Int): List<Step> {
        require(oldVersion >= MINIMUM_SUPPORTED_VERSION) { "schema version below the supported floor" }
        require(newVersion >= oldVersion) { "schema downgrade is unsupported" }
        val out = mutableListOf<Step>()
        var current = oldVersion
        while (current < newVersion) {
            val step = steps[current] ?: error("missing entity catalog migration $current -> ${current + 1}")
            require(step.to == current + 1) { "non-sequential entity catalog migration" }
            out += step; current = step.to
        }
        return out
    }
}
