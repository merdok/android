package io.panelassistant.android.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.dashboard.use
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class SqliteStatePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "sqlite-state-test"

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun failedSqliteMarkerCreateAndRemoveRetainStrictInProcessVisibilityUntilRetry() {
        val key = "tame_overlay.com.vendor.one"

        EntityCatalogStore(context).use { helper ->
            val state = SqliteStatePreferences(helper, namespace)
            val db = helper.writableDatabase
            db.execSQL(
                """CREATE TRIGGER fail_tame_insert BEFORE INSERT ON app_state
                   WHEN NEW.namespace='$namespace' AND NEW.state_key='$key'
                   BEGIN SELECT RAISE(ABORT, 'injected insert failure'); END""",
            )

            assertFalse(state.commitWithDurableVisibility { putString(key, "allow") })
            assertFalse(state.contains(key))
            assertFalse(sqliteContains(db, key))

            db.execSQL("DROP TRIGGER fail_tame_insert")
            assertTrue(state.commitWithDurableVisibility { putString(key, "allow") })
            assertEquals("allow", state.getString(key, null))
            assertTrue(sqliteContains(db, key))

            db.execSQL(
                """CREATE TRIGGER fail_tame_delete BEFORE DELETE ON app_state
                   WHEN OLD.namespace='$namespace' AND OLD.state_key='$key'
                   BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END""",
            )
            assertFalse(state.commitWithDurableVisibility { remove(key) })
            assertEquals("allow", state.getString(key, null))
            assertTrue(sqliteContains(db, key))

            db.execSQL("DROP TRIGGER fail_tame_delete")
            assertTrue(state.commitWithDurableVisibility { remove(key) })
            assertFalse(state.contains(key))
            assertFalse(sqliteContains(db, key))
        }
    }

    private fun sqliteContains(db: android.database.sqlite.SQLiteDatabase, key: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM app_state WHERE namespace=? AND state_key=?",
            arrayOf(namespace, key),
        ).use { it.moveToFirst() }

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }
}
