package io.panelassistant.android.dashboard

import android.database.SQLException
import java.io.File
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.panelassistant.android.persistence.ConfigVault
import io.panelassistant.android.storage.DatabaseBusyRetry
import io.panelassistant.android.storage.StorageHealthRuntime

internal enum class SchemaReconcileAction { NONE, BACKED_UP, RESTORED, PRESERVED_FRESH }

/** What the config vault put back into a freshly created database. */
internal data class ConfigRestore(val settings: Int, val profiles: Int)

/** Result of [reconcilePreOpen]; surfaced via EntityCatalogStorePreOpen.lastSchemaReconcile for health. */
internal data class SchemaReconcile(
    val action: SchemaReconcileAction,
    val fromVersion: Int,
    val toVersion: Int,
    val restoredVersion: Int = 0,
)

internal class DatabaseCompatibilityException(
    val refusal: DatabaseCompatibilityRefusal,
) : SQLException("database compatibility refused: $refusal")

/** Keep a meaningful downgrade outcome visible for the lifetime of the process. */
internal fun retainFirstSchemaReconcile(
    previous: SchemaReconcile?,
    current: SchemaReconcile,
): SchemaReconcile? = when {
    previous == null || previous.action == SchemaReconcileAction.NONE -> current
    current.action == SchemaReconcileAction.NONE -> previous
    else -> previous
}

private const val PREMIGRATE_SUFFIX = "premigrate"

private const val SUPERSEDED_SUFFIX = "superseded"

private const val MAX_PREMIGRATE_BACKUPS = 2

private const val MAX_PREMIGRATE_BYTES = 64L * 1024 * 1024

private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024

/** Path of the pre-migration snapshot taken at [version] for [target] (a restore source). */
internal fun preMigrationBackupFile(target: File, version: Int): File =
    File(target.parentFile, "${target.name}.v$version.$PREMIGRATE_SUFFIX")

/** Path where a too-new database is preserved for manual recovery (never a restore source). */
internal fun supersededFile(target: File, version: Int): File =
    File(target.parentFile, "${target.name}.v$version.$SUPERSEDED_SUFFIX")

/**
 * Reconcile the on-disk schema version against this build before the database is opened. Pure file
 * operations; [checkpoint] flushes WAL for the real store and is stubbed in tests. A refusal throws
 * before vault, checkpoint, or file mutations so SQLiteOpenHelper cannot bypass the decision.
 */
internal fun reconcilePreOpen(
    target: File,
    boundary: DatabaseCompatibilityBoundary,
    observation: DatabaseCompatibilityObservation,
    keepBackups: Int = MAX_PREMIGRATE_BACKUPS,
    maxBackupBytes: Long = MAX_PREMIGRATE_BYTES,
    freeSpace: (File) -> Long = { it.usableSpace },
    checkpoint: (File) -> Boolean = { true },
    vaultConfig: (File) -> Unit = {},
    revalidateObservation: () -> DatabaseCompatibilityObservation = { observation },
    stageRecovery: (RecoveryDatabaseObservation) -> File? = { it.file },
    restoreTransaction: DatabaseRestoreTransaction = DatabaseRestoreTransaction(target),
): SchemaReconcile {
    val currentVersion = boundary.maximumSchema
    when (val durable = restoreTransaction.reconcile()) {
        DatabaseRestoreResult.Absent -> Unit
        is DatabaseRestoreResult.Hold -> throw DatabaseRestoreHoldException(durable.reason)
        is DatabaseRestoreResult.Restored -> {
            if (durable.reconcile.toVersion != currentVersion ||
                durable.reconcile.restoredVersion != currentVersion
            ) throw DatabaseRestoreHoldException("database restore receipt is for another schema")
            return durable.reconcile
        }
    }
    val decision = DatabaseCompatibility.decide(
        boundary,
        observation,
        DatabaseOwnerState.RUNTIME_STARTUP,
    )
    if (decision is DatabaseCompatibilityDecision.Refuse) {
        throw DatabaseCompatibilityException(decision.reason)
    }
    // DB_COMPAT_MUTATION_ANCHOR: OBSERVATION_REVALIDATION
    // DB_COMPAT_MUTATION_ANCHOR: RUNTIME_FRESH_REVALIDATION
    // Fresh is only an observation, not a durable state. Reobserve it at the same last pre-open gate as
    // every existing database so a file, recovery, retained sidecar, or unreadable path that appears
    // after the initial observation cannot be opened, created over, or otherwise mutated by the helper.
    val revalidatedObservation = revalidateObservation()
    if (revalidatedObservation != observation ||
        DatabaseCompatibility.decide(
            boundary,
            revalidatedObservation,
            DatabaseOwnerState.RUNTIME_STARTUP,
        ) != decision
    ) {
        throw DatabaseCompatibilityException(
            DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION,
        )
    }
    if (decision is DatabaseCompatibilityDecision.Fresh) {
        return SchemaReconcile(SchemaReconcileAction.NONE, 0, currentVersion)
    }
    val onDiskVersion = (observation.primary as? PrimaryDatabaseObservation.Readable)?.schema
        ?: throw IllegalStateException("non-fresh decision requires readable primary database")
    if (decision is DatabaseCompatibilityDecision.Direct && onDiskVersion == currentVersion) {
        return SchemaReconcile(SchemaReconcileAction.NONE, onDiskVersion, currentVersion)
    }
    val restore = (decision as? DatabaseCompatibilityDecision.Recover)?.recovery
    val stagedRestore = restore?.let(stageRecovery)
    if (restore != null && stagedRestore == null) {
        throw DatabaseCompatibilityException(
            DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION,
        )
    }
    // The structure is about to change, the file is about to be replaced, or an older build is about to
    // operate on a newer one. Copy configuration out first, unconditionally: it is a fraction of a
    // percent of the bytes and effectively all of the value, whereas the whole-database snapshot below is
    // deliberately skipped when the file is large or space is short — exactly the conditions under which
    // loss is most likely. It dumps raw rows, so it captures keys this build does not itself understand.
    runCatching { vaultConfig(target) }
    if (decision is DatabaseCompatibilityDecision.Direct && onDiskVersion < currentVersion) {
        // Upgrade imminent: snapshot the pre-migration structure so a later downgrade can roll back.
        // Space-aware and best-effort — an upgrade must never fail for lack of room to archive a copy.
        val backedUp = writePreMigrationBackup(
            target,
            onDiskVersion,
            keepBackups,
            maxBackupBytes,
            freeSpace,
            checkpoint,
        )
        return SchemaReconcile(
            if (backedUp) SchemaReconcileAction.BACKED_UP else SchemaReconcileAction.NONE,
            onDiskVersion,
            currentVersion,
        )
    }
    try {
        restore ?: throw IllegalStateException("non-direct decision requires recovery")
        when (val restored = restoreTransaction.restore(
            staged = checkNotNull(stagedRestore),
            sourceSchema = onDiskVersion,
            stagedSchema = checkNotNull(restore.namedSchema),
            checkpoint = checkpoint,
        )) {
            DatabaseRestoreResult.Absent -> throw IllegalStateException("database restore did not start")
            is DatabaseRestoreResult.Hold -> throw DatabaseRestoreHoldException(restored.reason)
            is DatabaseRestoreResult.Restored -> return restored.reconcile
        }
    } finally {
        if (stagedRestore != null && stagedRestore != restore?.file) runCatching { stagedRestore.delete() }
    }
}

private data class VersionedFile(val file: File, val version: Int)

private fun listVersioned(target: File, suffix: String): List<VersionedFile> {
    val dir = target.parentFile ?: return emptyList()
    val pattern = Regex("^" + Regex.escape(target.name) + "\\.v(\\d+)\\." + suffix + "$")
    return (dir.listFiles() ?: emptyArray()).mapNotNull { file ->
        pattern.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()?.let { VersionedFile(file, it) }
    }
}

private fun pruneVersioned(target: File, suffix: String, keep: Int) {
    listVersioned(target, suffix).sortedByDescending { it.version }.drop(keep.coerceAtLeast(0))
        .forEach { runCatching { it.file.delete() } }
}

private fun writePreMigrationBackup(
    target: File,
    version: Int,
    keepBackups: Int,
    maxBytes: Long,
    freeSpace: (File) -> Long,
    checkpoint: (File) -> Boolean,
): Boolean {
    return runCatching {
        if (!target.isFile || !checkpoint(target)) return false
        val size = target.length()
        if (size > maxBytes) return false
        val dir = target.parentFile ?: return false
        // Retain at most keepBackups-1 existing snapshots so the new copy stays within budget, then drop
        // further snapshots while disk space is tight. An upgrade must never fail for lack of room to
        // archive a rollback copy: a reduced or absent backup is acceptable, a failed upgrade is not.
        var keepExisting = (keepBackups - 1).coerceAtLeast(0)
        pruneVersioned(target, PREMIGRATE_SUFFIX, keepExisting)
        while (freeSpace(dir) < size + SPACE_MARGIN_BYTES && keepExisting > 0) {
            keepExisting--
            pruneVersioned(target, PREMIGRATE_SUFFIX, keepExisting)
        }
        if (freeSpace(dir) < size + SPACE_MARGIN_BYTES) return false // no room even after pruning — skip
        val dest = preMigrationBackupFile(target, version)
        val tmp = File(dest.path + ".tmp")
        target.copyTo(tmp, overwrite = true)
        val installed = tmp.length() == size && tmp.renameTo(dest) && dest.isFile && dest.length() == size
        if (!installed) tmp.delete()
        installed
    }.getOrElse {
        runCatching { File(preMigrationBackupFile(target, version).path + ".tmp").delete() }
        false
    }
}

/** Everything that runs before [EntityCatalogStore] opens the database, and the vault that survives it. */
internal object EntityCatalogStorePreOpen {
    /** Mirrors RuntimeProfileRegistry's imported catalog; these live outside the database. */
    private const val IMPORTED_PROFILE_DIRECTORY = "device-profiles/imported"

    /** A profile is a small YAML document; anything larger is not one and is not worth vaulting. */
    private const val MAX_VAULTED_PROFILE_BYTES = 256L * 1024

    // ---- Schema downgrade safety net (Tier-2). See reconcilePreOpen() below. ----
    internal val DATABASE_RESTORE_OPEN_LEASE = DatabaseRestoreOpenLease()

    private fun retainDatabaseFailure(operation: String, failure: Throwable) {
        if (failure is SQLException) StorageHealthRuntime.recordDatabaseFailure(operation, failure)
    }

    /** Outcome of the most recent pre-open schema reconciliation, for health reporting. */
    @Volatile
    internal var lastSchemaReconcile: SchemaReconcile? = null

    /**
     * Set when configuration was recovered from the vault into a freshly created database. Read by
     * health so the schema warning can say what actually happened: "settings may have reset" is
     * untrue once they have been restored, and telling an owner to re-enter working configuration is
     * its own kind of damage.
     */
    internal var lastConfigRestore: ConfigRestore? = null
        private set

    /** Runs the authoritative pre-open decision; refusal must prevent SQLiteOpenHelper from opening. */
    internal fun reconcileSchema(context: Context): SchemaReconcile {
        return try {
            val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
            val boundary = EntityCatalogSchema.DATABASE_COMPATIBILITY
            val restore = DatabaseRestoreTransaction(target)
            val resumed = restore.reconcile()
            if (resumed is DatabaseRestoreResult.Hold) {
                throw DatabaseRestoreHoldException(resumed.reason)
            }
            val observation = DatabaseCompatibility.observe(context, boundary)
            (observation.primary as? PrimaryDatabaseObservation.Unreadable)?.let { unreadable ->
                val failure = SQLException(unreadable.detail ?: "database observation unreadable")
                retainDatabaseFailure("database-version-read", failure)
            }
            val outcome = reconcilePreOpen(
                target,
                boundary,
                observation,
                checkpoint = ::checkpointDatabaseFile,
                vaultConfig = { database -> vaultConfiguration(context, database) },
                revalidateObservation = { DatabaseCompatibility.observe(context, boundary) },
                stageRecovery = { recovery -> stageValidatedRecovery(recovery, context.cacheDir) },
                restoreTransaction = restore,
            )
            lastSchemaReconcile = retainFirstSchemaReconcile(lastSchemaReconcile, outcome)
            outcome
        } catch (failure: Throwable) {
            retainDatabaseFailure("database-preopen-reconcile", failure)
            throw failure
        }
    }

    /**
     * Copies configuration and imported device profiles into the vault before the structure changes.
     *
     * Reads the database directly rather than going through [AppState] so it does not depend on the
     * app's object model or on the schema version, and opens read-only so a failure here can never
     * damage the database it is protecting. Best-effort by design: an unreadable database yields no
     * rows, and [ConfigVault.write] refuses an empty export rather than overwriting good generations.
     */
    private fun vaultConfiguration(context: Context, database: File) {
        if (!database.isFile) return
        val rows = runCatching {
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                db.rawQuery(
                    "SELECT namespace,state_key,value_type,value_text,updated_at FROM app_state",
                    emptyArray(),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                ConfigVault.StateRow(
                                    namespace = cursor.getString(0),
                                    key = cursor.getString(1),
                                    type = cursor.getString(2),
                                    valueText = if (cursor.isNull(3)) null else cursor.getString(3),
                                    updatedAt = cursor.getLong(4),
                                ),
                            )
                        }
                    }
                }
            }
        }.onFailure { retainDatabaseFailure("database-vault-read", it) }.getOrDefault(emptyList())

        val profiles = runCatching { vaultedProfiles(context) }.getOrDefault(emptyMap())

        ConfigVault.write(
            File(context.filesDir, ConfigVault.VAULT_DIRECTORY),
            ConfigVault.Export(rows, profiles),
            System.currentTimeMillis(),
        )
    }

    /** Imported device profiles, which live outside the database, as the vault carries them. */
    internal fun vaultedProfiles(context: Context): Map<String, String> =
        File(context.filesDir, IMPORTED_PROFILE_DIRECTORY).listFiles()
            ?.filter { it.isFile && it.length() <= MAX_VAULTED_PROFILE_BYTES }
            ?.associate { it.name to it.readText() }
            .orEmpty()

    /**
     * Restores vaulted configuration and imported profiles into a freshly created database.
     *
     * Only ever called from [EntityCatalogStore.onCreate], so `app_state` is empty by construction; the emptiness check
     * is kept anyway because restoring *over* live configuration is the one genuinely dangerous
     * direction and must be impossible rather than merely unreachable. Only a generation whose digest
     * verifies is considered, and a failure leaves the fresh database untouched rather than
     * half-populated — losing a recovery is recoverable, corrupting one is not.
     *
     * On first install the vault is absent and this is a no-op. Clearing app data removes the vault
     * along with the database, so a deliberate reset stays a reset.
     */
    internal fun restoreConfigurationInto(db: SQLiteDatabase, context: Context) {
        runCatching {
            val vaultDir = File(context.filesDir, ConfigVault.VAULT_DIRECTORY)
            val export = ConfigVault.newestValid(vaultDir) ?: return
            if (countRows(db, "app_state") != 0) return

            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(?,?,?)",
                    arrayOf<Any?>(System.currentTimeMillis(), "config", "config-vault"),
                )
                val revision = countRows(db, "app_state_revision").toLong()
                export.rows.map { it.namespace }.distinct().forEach { namespace ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO app_state_namespace(namespace,imported_at,legacy_name) VALUES(?,?,'')",
                        arrayOf<Any?>(namespace, System.currentTimeMillis()),
                    )
                }
                export.rows.forEach { row ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO app_state(namespace,state_key,value_type,value_text,updated_at,revision) " +
                            "VALUES(?,?,?,?,?,?)",
                        arrayOf<Any?>(row.namespace, row.key, row.type, row.valueText, row.updatedAt, revision),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            // Profiles are files, so they are restored only where nothing occupies the name; an
            // existing profile is always more current than a vaulted copy.
            val importedDir = File(context.filesDir, IMPORTED_PROFILE_DIRECTORY)
            export.profiles.forEach { (name, content) ->
                val destination = File(importedDir, name)
                if (!destination.exists() && destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                    runCatching { destination.writeText(content) }
                }
            }
            lastConfigRestore = ConfigRestore(export.rows.size, export.profiles.size)
        }.onFailure { retainDatabaseFailure("database-vault-restore", it) }
    }

    private fun countRows(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT count(*) FROM $table", emptyArray()).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /**
     * Flushes WAL into the main file so a single-file pre-migration copy is complete.
     *
     * The checkpoint opens its own raw connection, so a concurrent instance's routine write makes
     * it report busy — the app's own concurrency, not a storage fault (Issue #91). A busy result
     * or a BUSY-classified throw is re-attempted within one bounded budget before latching.
     */
    private fun checkpointDatabaseFile(database: File): Boolean {
        val retry = DatabaseBusyRetry().begin()
        while (true) {
            try {
                val completed = SQLiteDatabase.openDatabase(
                    database.path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                        SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                ).use { opened ->
                    opened.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        cursor.moveToFirst() && cursor.columnCount == 3 &&
                            cursor.getLong(0) == 0L && cursor.getLong(1) == 0L &&
                            cursor.getLong(2) == 0L && !cursor.moveToNext()
                    }
                }
                if (completed) {
                    StorageHealthRuntime.recordDatabaseWriteSuccess()
                    return true
                }
                val busy = IllegalStateException("database is busy")
                if (!retry.admitRetry(busy)) {
                    StorageHealthRuntime.recordDatabaseFailure("database-checkpoint", busy)
                    return false
                }
            } catch (failure: Throwable) {
                if (failure is SQLException && retry.admitRetry(failure)) continue
                StorageHealthRuntime.recordDatabaseFailure("database-checkpoint", failure)
                return false
            }
        }
    }
}
