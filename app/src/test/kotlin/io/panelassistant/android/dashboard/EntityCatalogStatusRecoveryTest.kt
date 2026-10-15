package io.panelassistant.android.dashboard

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A runtime promotion returning a dashboard to active after the promotion cap marked it degraded,
 * run as the store runs it ([EntityCatalogStore.RECOVER_STATUS_SQL]) against real SQLite and the store's
 * real `dashboard` table. A sync or probe failure does not take the learner's monitor, so it can write
 * a newer degraded error between the promotion's decision and its write; that error must survive.
 */
class EntityCatalogStatusRecoveryTest {
    private lateinit var directory: File
    private lateinit var db: Connection

    private val capError = "runtime dependencies queued; synchronize manually"

    @Before fun createDatabase() {
        directory = Files.createTempDirectory("status-recovery").toFile()
        db = DriverManager.getConnection("jdbc:sqlite:${File(directory, "catalog.db").path}")
        exec(EntityCatalogSchema.DASHBOARD_TABLE_SQL)
    }

    @After fun closeDatabase() {
        runCatching { db.close() }
        directory.deleteRecursively()
    }

    private fun exec(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    /** What markStatus leaves behind. */
    private fun status(path: String, status: String, error: String) =
        exec("INSERT OR REPLACE INTO dashboard(instance,path,status,error) VALUES('ha',?,?,?)", path, status, error)

    private fun recover(path: String): Int = exec(EntityCatalogStore.RECOVER_STATUS_SQL, "ha", path, capError)

    private fun row(path: String): Pair<String, String> =
        db.prepareStatement("SELECT status,error FROM dashboard WHERE instance='ha' AND path=?").use { statement ->
            statement.setString(1, path)
            statement.executeQuery().use { rows -> rows.next(); rows.getString(1) to rows.getString(2) }
        }

    @Test fun aPromotionClearsTheDegradedStatusTheCapWrote() {
        status("lovelace", "degraded", capError)
        status("other", "degraded", capError)

        assertEquals(1, recover("lovelace"))

        assertEquals("active" to "", row("lovelace"))
        assertEquals("only the promoted dashboard recovers", "degraded" to capError, row("other"))
    }

    @Test fun aSyncFailureWrittenAfterTheCapSurvivesThePromotion() {
        status("lovelace", "degraded", capError)
        // The daily sync fails between the promotion's decision and its write.
        status("lovelace", "degraded", "timeout waiting for Home Assistant")

        assertEquals(0, recover("lovelace"))

        assertEquals("degraded" to "timeout waiting for Home Assistant", row("lovelace"))
    }
}
