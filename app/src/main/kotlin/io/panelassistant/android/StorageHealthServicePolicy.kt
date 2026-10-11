package io.panelassistant.android

import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageHealthObservation
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck

// The service's pure storage-health policy: the notification it shows and the daily probe's retry rule.

internal sealed interface StorageHealthNotificationDecision {
    data object KeepExisting : StorageHealthNotificationDecision
    data object Cancel : StorageHealthNotificationDecision
    data class Show(val title: String, val body: String) : StorageHealthNotificationDecision
}

/** Pure notification projection: the storage authority owns thresholds, hysteresis and failure latching. */
internal fun storageHealthNotificationDecision(
    snapshot: StorageHealthSnapshot,
): StorageHealthNotificationDecision = when (snapshot.severity) {
    StorageHealthSeverity.UNCHECKED -> StorageHealthNotificationDecision.KeepExisting
    StorageHealthSeverity.HEALTHY,
    StorageHealthSeverity.WARNING -> StorageHealthNotificationDecision.Cancel
    StorageHealthSeverity.CRITICAL -> {
        val capacity = if (snapshot.totalBytes > 0L) {
            " (${snapshot.usableBytes.coerceAtLeast(0L) / (1024L * 1024L)} MiB filesystem free)"
        } else ""
        StorageHealthNotificationDecision.Show(
            title = "Panel storage/database pressure critical",
            body = "Storage or database-file pressure is critical$capacity. " +
                "Open Panel Assistant and review free space and WAL growth before changing configuration.",
        )
    }
    StorageHealthSeverity.DATABASE_FAILURE -> {
        // The operation class is the one detail that turns "something failed" into a reproducible
        // report; it is a closed internal vocabulary, so it carries no query text or user data.
        val during = snapshot.databaseFailureOperationLabel?.let { " during $it" }.orEmpty()
        StorageHealthNotificationDecision.Show(
            title = "Panel database needs attention",
            body = when (snapshot.databaseFailureKind) {
                StorageDatabaseFailureKind.STORAGE_FULL ->
                    "A panel database write$during failed when storage was full. Open Panel Assistant; recovery is not yet verified."
                StorageDatabaseFailureKind.IO ->
                    "The panel database reported a disk I/O failure$during. Open Panel Assistant; retry only after resolving the cause."
                StorageDatabaseFailureKind.CORRUPTION ->
                    "The panel database failed its integrity check$during. Preserve it and open Panel Assistant diagnostics before further writes."
                StorageDatabaseFailureKind.BUSY ->
                    "The panel database remained busy or locked$during. Open Panel Assistant diagnostics and wait for a clean check before retrying."
                StorageDatabaseFailureKind.UNKNOWN,
                null -> "The panel database reported a failure$during. Preserve it and open Panel Assistant diagnostics before retrying."
            },
        )
    }
}

internal enum class StorageHealthObservationQuality { COMPLETE, FAILED, INCOMPLETE }

/** Pure admission rule for the daily probe's bounded retry loop. */
internal fun storageHealthObservationQuality(
    observation: StorageHealthObservation,
): StorageHealthObservationQuality = when {
    observation.quickCheck == StorageQuickCheck.FAILED -> StorageHealthObservationQuality.FAILED
    observation.checkedAtMillis <= 0L || observation.totalBytes <= 0L ||
        observation.mainDatabaseBytes <= 0L || observation.pageSizeBytes <= 0L ||
        observation.pageCount <= 0L || observation.schemaVersion <= 0 ||
        observation.quickCheck == StorageQuickCheck.NOT_RUN -> StorageHealthObservationQuality.INCOMPLETE
    else -> StorageHealthObservationQuality.COMPLETE
}

internal fun storageHealthObservationNeedsRetry(observation: StorageHealthObservation): Boolean =
    storageHealthObservationQuality(observation) == StorageHealthObservationQuality.INCOMPLETE

internal sealed interface StorageHealthObservationAttempt {
    data object Complete : StorageHealthObservationAttempt
    data object Retry : StorageHealthObservationAttempt
    data object Stopped : StorageHealthObservationAttempt
}

/** A stopped or complete observation must not spend another prompt-recovery attempt. */
internal fun storageHealthRecoveryAttemptComplete(attempt: StorageHealthObservationAttempt): Boolean =
    attempt != StorageHealthObservationAttempt.Retry
