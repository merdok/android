package io.panelassistant.android

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notice's lifetime on a panel upgraded from a broker-era install; who gets it is in HealthRoutesHttpTest. */
class MigrationNoticeConfigTest {
    @Test fun `unmanaged panel keeps its notice across restarts`() {
        val state = Preferences()
        assertTrue(Config(state.preferences).migrationNoticeVisible(100))
        assertTrue(Config(state.preferences).migrationNoticeVisible(100))
    }

    @Test fun `accepted Panel Assistant session clears the notice through the production config writer`() {
        val config = Config(Preferences().preferences)
        assertTrue(config.migrationNoticeVisible(100))
        config.markPanelAssistantConnected()
        assertFalse(config.migrationNoticeVisible(100))
        assertFalse(config.migrationNoticeVisible(101))
    }

    @Test fun `a panel Panel Assistant is adding does not tell its owner to install Panel Assistant`() {
        val state = Preferences()
        Config(state.preferences).setHaSetupHandover(true)
        assertFalse(Config(state.preferences).migrationNoticeVisible(100))
    }

    @Test fun `dismissal survives restart and notice returns after an update`() {
        val state = Preferences()
        val config = Config(state.preferences)
        assertTrue(config.dismissMigrationNotice(100))
        assertFalse(config.migrationNoticeVisible(100))
        assertFalse(Config(state.preferences).migrationNoticeVisible(100))
        assertTrue(Config(state.preferences).migrationNoticeVisible(101))
        assertFalse(config.panelAssistantConnectionSeen)
    }

    @Test fun `failed dismissal keeps the notice visible`() {
        val state = Preferences(commitSucceeds = false)
        val config = Config(state.preferences)
        assertFalse(config.dismissMigrationNotice(100))
        assertTrue(config.migrationNoticeVisible(100))
    }

    @Test fun `restored connection history suppresses the notice`() {
        val state = Preferences()
        state.preferences.edit().putBoolean("migration_notice_connection_seen", true).commit()
        val successor = Config(state.preferences)
        assertFalse(successor.migrationNoticeVisible(101))
    }

    private class Preferences(private val commitSucceeds: Boolean = true) {
        private val values = mutableMapOf<String, Any?>(Config.LEGACY_ORIGIN_PREF to true)
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getString", "getInt", "getLong", "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { pending[args!![0] as String] = args[1]; proxy }
                    method.name == "apply" -> { values.putAll(pending); null }
                    method.name == "commit" -> { if (commitSucceeds) values.putAll(pending); commitSucceeds }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
