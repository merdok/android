package io.panelassistant.android

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.persistence.ConfigVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each setting has one stored key and one validation path: the typed writers store what their spec
 *  stores, and the live-store migration folds the controllers' old keys, so old backups still restore. */
class ConfigOneKeyPerSettingTest {
    @Test fun typedWritersStoreWhatTheirSpecStoresAndRefuseWhatItRefuses() {
        val prefs = configTestPreferences()
        val config = Config(prefs.instance)

        config.setTameVendorPackages(" com.vendor.one, android com.vendor.one\ncom.vendor.two ")
        assertEquals("com.vendor.one com.vendor.two", config.tameVendorPackagesRaw)
        for (refused in listOf("com.good;reboot", (0..128).joinToString(" ") { "com.vendor.pkg$it" })) {
            assertTrue(runCatching { config.setTameVendorPackages(refused) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals("com.vendor.one com.vendor.two", config.tameVendorPackagesRaw)
        }

        assertTrue(config.setHaConnection(" http://ha.local:8123/ ", "token-one"))
        assertEquals("http://ha.local:8123", config.haUrl)
        for (refused in listOf("http://owner:secret@ha.local:8123", "ftp://ha.local", "http://ha.local:8123/?next=1")) {
            assertFalse(refused, config.setHaConnection(refused, "token-two"))
            assertFalse(refused, config.setBorrowedRendererSettings(refused, "token-two", "refresh", 1L, "client", 90))
            assertEquals("http://ha.local:8123", config.haUrl)
            assertEquals("token-one", config.haToken)
            assertFalse(config.rendererLaunchPending)
        }
    }

    @Test fun aConfigurationVaultWrittenBeforeTheOneKeyChangeRestoresWhatThePanelWasDoing() {
        // The rows a build before the change vaulted: each controller's state under its own key, and a
        // registry copy of network ADB that the controller had not followed.
        fun row(key: String, type: String, text: String) =
            ConfigVault.StateRow("config", key, type, text, 0L)
        val vaulted = ConfigVault.encode(
            ConfigVault.Export(
                listOf(
                    row("config_schema", "int", SettingsRegistry.SCHEMA.toString()),
                    row("network_adb_enabled", "boolean", "1"),
                    row("network_adb", "boolean", "0"),
                    row("zigbee_router_enabled", "boolean", "1"),
                    row("zigbee_router_configured", "boolean", "1"),
                ),
                emptyMap(),
            ),
        )
        val restored = requireNotNull(ConfigVault.decode(vaulted)).rows
            .filter { it.namespace == "config" }
            .associate { it.key to if (it.type == "boolean") it.valueText == "1" else it.valueText!!.toInt() }
        val prefs = configTestPreferences(initial = restored)

        // Recovery writes the rows into a fresh database; the next service start runs the live-store migration.
        assertTrue(Config(prefs.instance).migrateLiveStore())
        val config = Config(prefs.instance)
        assertTrue(config.networkAdbEnabled)
        assertTrue(config.zigbeeRouterEnabled)
        assertTrue(config.zigbeeRouterConfigured)
        assertEquals("true", config.getRaw(SettingsRegistry.spec("network_adb")!!))
        assertEquals("true", config.getRaw(SettingsRegistry.spec("zigbee_router")!!))
        // After the restore, the owner's next save is what the panel acts on.
        assertTrue(config.commitRaw(SettingsRegistry.spec("zigbee_router")!!, "false"))
        assertFalse(config.zigbeeRouterEnabled)
        assertTrue(config.setNetworkAdbEnabled(false))
        assertEquals("false", config.getRaw(SettingsRegistry.spec("network_adb")!!))
    }

    @Test fun liveMigrationFoldsTheControllerKeysIntoEachSettingsOwnKey() {
        for (schema in listOf(14, SettingsRegistry.SCHEMA)) {
            // The controllers acted on their own keys; a registry write they had not followed disagrees.
            val prefs = configTestPreferences(initial = mapOf(
                "config_schema" to schema,
                "network_adb_enabled" to true, "network_adb" to false,
                "zigbee_router_enabled" to false, "zigbee_router_configured" to true,
            ))
            val config = Config(prefs.instance)

            assertTrue("migration from schema $schema", config.migrateLiveStore())
            assertTrue(config.networkAdbEnabled)
            assertFalse(config.zigbeeRouterEnabled)
            assertTrue(config.zigbeeRouterConfigured)
            assertEquals(true, prefs.values["network_adb"])
            assertEquals(false, prefs.values["zigbee_router"])
            assertEquals("true", config.getRaw(SettingsRegistry.spec("network_adb")!!))
            assertEquals("false", config.getRaw(SettingsRegistry.spec("zigbee_router")!!))
            assertFalse(prefs.values.containsKey("network_adb_enabled"))
            assertFalse(prefs.values.containsKey("zigbee_router_enabled"))
            val committed = prefs.values.toMap()
            assertTrue(Config(prefs.instance).migrateLiveStore())
            assertEquals("a second startup must be a no-op", committed, prefs.values)
        }
    }
}
