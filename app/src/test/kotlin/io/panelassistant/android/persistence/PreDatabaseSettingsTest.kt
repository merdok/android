package io.panelassistant.android.persistence

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.FakePreferences
import io.panelassistant.android.PreDatabaseSettings
import io.panelassistant.android.darkModeBeforeDatabase
import io.panelassistant.android.fakePreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What `Application.onCreate` reads before the database opens, as the `config` namespace leaves it. */
class PreDatabaseSettingsTest {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    @After fun stop() {
        writer.shutdownNow()
    }

    @Test fun aSavedLanguageAndThemeAreWhatTheNextColdStartReads() {
        val context = FileContext(fakePreferences(mapOf("dark_mode" to true)))
        val config = configState(context, InMemoryState(emptyMap()))

        assertTrue(config.edit().putString("ui_language", "de").putBoolean("dark_mode", false).commit())

        assertFalse(darkModeBeforeDatabase(context))
        assertEquals("de", context.file.instance.getString(PreDatabaseSettings.UI_LANGUAGE_KEY, null))
    }

    @Test fun anUpdatedPanelsFullOldCopyIsTrimmedToTheDatabasesTwoSettings() {
        // What a 0.9.x build left in the file: its whole config mirror, the language a downgrade last set.
        val context = FileContext(
            fakePreferences(
                mapOf(
                    "ha_url" to "http://ha.local:8123",
                    "ha_token" to "old-access-token",
                    "ui_language" to "en",
                    "dark_mode" to true,
                ),
            ),
        )
        configState(
            context,
            InMemoryState(mapOf("ha_url" to "http://ha.local:8123", "ui_language" to "fr", "dark_mode" to false)),
        )

        assertEquals(mapOf("ui_language" to "fr", "dark_mode" to false), context.file.values)
        assertFalse(darkModeBeforeDatabase(context))
    }

    @Test fun aSaveTheDatabaseRefusesLeavesTheColdStartOnTheOldValue() {
        val context = FileContext(fakePreferences(mapOf("dark_mode" to true)))
        val database = InMemoryState(mapOf("dark_mode" to true), persistSucceeds = false)
        val config = configState(context, database)

        assertFalse(config.edit().putBoolean("dark_mode", false).commit())

        assertTrue(darkModeBeforeDatabase(context))
    }

    @Test fun otherSettingsNeverReachTheFile() {
        val context = FileContext(fakePreferences())
        val config = configState(context, InMemoryState(emptyMap()))

        assertTrue(config.edit().putString("ha_token", "secret").putString("ha_url", "http://ha").commit())

        assertEquals(emptyMap<String, Any?>(), context.file.values)
    }

    private fun configState(context: Context, database: StateNamespacePersistence): SharedPreferences =
        SqliteStatePreferences(
            PreDatabaseSettingsPersistence(
                database,
                context.getSharedPreferences(PreDatabaseSettings.FILE, Context.MODE_PRIVATE),
            ),
            writer,
        )

    private class FileContext(val file: FakePreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            check(name == PreDatabaseSettings.FILE) { "unexpected preference file $name" }
            return file.instance
        }
    }

    private class InMemoryState(
        initial: Map<String, Any>,
        private val persistSucceeds: Boolean = true,
    ) : StateNamespacePersistence {
        private val values = initial.toMutableMap()

        override fun initialize(): Map<String, Any> = values.toMap()

        override fun persist(mutation: StateMutation): Boolean {
            if (!persistSucceeds) return false
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }
    }
}
