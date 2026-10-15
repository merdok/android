package io.panelassistant.android

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.CancellationSignal
import io.panelassistant.android.storage.StorageHealthObservation
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.storage.VacuumOutcome
import io.panelassistant.android.storage.VacuumResult
import io.panelassistant.android.storage.WalCheckpointOutcome
import io.panelassistant.android.storage.WalCheckpointResult
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The service's storage-health owner, driven through the entry points the service calls: the status
 * refresh, the daily check started at runtime start, and close at teardown. The database is a fake
 * that records which operations ran, because what matters is whether maintenance touched the data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStorageHealthTest {
    private class RecordingDatabase(private val observation: StorageHealthObservation) : ServiceStorageHealth.Database {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun storageHealthObservation(cancellationSignal: CancellationSignal): StorageHealthObservation {
            calls += "observe"
            return observation
        }

        override fun enforceHistoryRetention(): Boolean {
            calls += "retention"
            return false
        }

        override fun truncateDatabaseWal(): WalCheckpointOutcome {
            calls += "checkpoint"
            return WalCheckpointOutcome(WalCheckpointResult.NOT_NEEDED, 0L, 0L)
        }

        override fun writeVerifiedConfigurationBackup(): Boolean {
            calls += "backup"
            return false
        }

        override fun vacuumDatabase(): VacuumOutcome {
            calls += "vacuum"
            return VacuumOutcome(VacuumResult.FAILED)
        }
    }

    private val root: File = Files.createTempDirectory("service-storage-health").toFile()

    /** Real directories, so the remediation sweep has somewhere to look when a test reaches it. */
    private val context = object : ContextWrapper(null) {
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getApplicationInfo(): ApplicationInfo =
            ApplicationInfo().apply { sourceDir = File(root, "base.apk").path }
    }

    /** The health authority is process-wide: start every test from a clean, unlatched state. */
    @Before fun clearDatabaseFailureLatch() {
        StorageHealthRuntime.refresh(observation(usedPercent = 50))
        StorageHealthRuntime.recordDatabaseWriteSuccess()
    }

    @Test fun aStatusRefreshReportsTheCleanObservationItJustTook() = runBlocking {
        val taken = observation(usedPercent = 50)
        val database = RecordingDatabase(taken)

        val snapshot = unit(database, CoroutineScope(coroutineContext)).refreshStorageHealthForStatus()

        assertNotNull("a clean observation earns a status snapshot", snapshot)
        assertEquals(taken.checkedAtMillis, snapshot!!.checkedAtMillis)
        assertEquals(listOf("observe"), database.calls)
    }

    @Test fun aStoppingServiceTakesNoObservationForStatus() = runBlocking {
        val database = RecordingDatabase(observation(usedPercent = 50))

        val snapshot = unit(database, CoroutineScope(coroutineContext), stopping = true).refreshStorageHealthForStatus()

        assertNull(snapshot)
        assertEquals(emptyList<String>(), database.calls)
    }

    @Test fun nothingReadsTheDatabaseAfterTheOwnerCloses() = runBlocking {
        val database = RecordingDatabase(observation(usedPercent = 50))
        val health = unit(database, CoroutineScope(coroutineContext))

        health.close()

        assertNull(health.refreshStorageHealthForStatus())
        assertEquals(emptyList<String>(), database.calls)
    }

    @Test fun theDailyCheckOnAHealthyPanelConvergesHistoryRetention() = runTest {
        val database = RecordingDatabase(observation(usedPercent = 50))
        val health = unit(database, backgroundScope)

        health.startStorageHealthChecks()
        runCurrent()

        assertEquals(listOf("observe", "retention"), database.calls)
        health.close()
    }

    @Test fun remediationUnderPressureLeavesTheDataAloneWhileACompanionOperationOwnsIt() = runTest {
        val database = RecordingDatabase(observation(usedPercent = 90))
        val health = unit(database, backgroundScope, companionPending = true)

        health.startStorageHealthChecks()
        runCurrent()

        assertTrue("the daily check must still observe", "observe" in database.calls)
        for (write in listOf("retention", "checkpoint", "backup", "vacuum")) {
            assertFalse("$write must wait for the companion operation", write in database.calls)
        }
        health.close()
    }

    @Test fun remediationUnderPressurePrunesHistoryWhenNothingElseOwnsTheData() = runTest {
        val database = RecordingDatabase(observation(usedPercent = 90))
        val health = unit(database, backgroundScope, companionPending = false)

        health.startStorageHealthChecks()
        runCurrent()

        assertTrue("retention runs under pressure: ${database.calls}", "retention" in database.calls)
        health.close()
    }

    private fun unit(
        database: ServiceStorageHealth.Database,
        scope: CoroutineScope,
        stopping: Boolean = false,
        companionPending: Boolean = false,
    ) = ServiceStorageHealth(context, scope, { stopping }, database, { companionPending })

    /** A clean SQLite read on a 10 GiB filesystem with [usedPercent] of it used and an empty WAL. */
    private fun observation(usedPercent: Int): StorageHealthObservation {
        val total = 10L * 1024L * 1024L * 1024L
        return StorageHealthObservation(
            checkedAtMillis = System.currentTimeMillis(),
            usableBytes = total / 100L * (100L - usedPercent),
            totalBytes = total,
            mainDatabaseBytes = 4L * 1024L * 1024L,
            walBytes = 0L,
            sidecarBytes = 0L,
            pageSizeBytes = 4096L,
            pageCount = 1024L,
            freelistCount = 0L,
            schemaVersion = 14,
            quickCheck = StorageQuickCheck.OK,
        )
    }
}
