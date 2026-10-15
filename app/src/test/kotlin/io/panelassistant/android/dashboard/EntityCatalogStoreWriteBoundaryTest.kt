package io.panelassistant.android.dashboard

import android.content.ContextWrapper
import android.database.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The write boundary every catalogue, maintenance, ambient and proximity write goes through. Each of
 * the app's connection pools can lose a write to another one; that is contention, not a fault, so
 * the losing write is run again within its budget rather than failing the caller.
 */
class EntityCatalogStoreWriteBoundaryTest {
    private val directory = Files.createTempDirectory("catalog-write-boundary").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
        override fun getNoBackupFilesDir(): File = directory
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun getApplicationContext() = this
    }
    private val store = EntityCatalogStore(context)

    @After fun cleanUp() {
        store.close()
        directory.deleteRecursively()
    }

    /** What Android throws when another connection holds the write lock. */
    private class SQLiteDatabaseLockedException : SQLException() {
        override val message = "database is locked (code 5 SQLITE_BUSY)"
    }

    @Test fun aWriteThatLosesALockRaceIsRunAgainAndSucceeds() {
        var attempts = 0
        val written = runCatching {
            store.observedWrite("catalog-status") {
                attempts++
                if (attempts == 1) throw SQLiteDatabaseLockedException()
                "written"
            }
        }

        assertEquals("the caller gets its write, not the lost race", "written", written.getOrNull())
        assertEquals(2, attempts)
    }
}
