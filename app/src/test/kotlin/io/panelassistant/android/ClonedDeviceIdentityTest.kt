package io.panelassistant.android

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateNamespacePersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Two panels flashed from one factory image share the Android 8.1 SSAID seed, so they published one
 * identical `ha-paneld-aid-<ANDROID_ID>` device identifier. Home Assistant merges a device on ANY
 * matching identifier, so those panels collapsed into a single device: entities named after another
 * panel, no per-panel area, device page or device automations (#155).
 *
 * These tests hold the identity apart at each place the old value reached: what discovery reports,
 * what a generated panel id is built from, and what a settings archive is allowed to carry.
 */
class ClonedDeviceIdentityTest {

    private val clonedAndroidId = "9f86d081884c7d65"

    @Test fun clonedAndroidIdsHaveDistinctDiscoveryHealthAndStableInstallIdentities() {
        val firstStore = RestoredState(emptyMap())
        val secondStore = RestoredState(emptyMap())
        val first = config(firstStore)
        val second = config(secondStore)
        first.ensureDeviceUid()
        second.ensureDeviceUid()
        val firstHealth = io.panelassistant.android.http.panelAssistantDiscoveryHealthToken(first.deviceUid, clonedAndroidId)
        val secondHealth = io.panelassistant.android.http.panelAssistantDiscoveryHealthToken(second.deviceUid, clonedAndroidId)
        fun token(health: String, name: String) = health.trim().split(" ").single { it.startsWith("$name=") }.substringAfter('=')
        assertNotEquals(token(firstHealth, "did"), token(secondHealth, "did"))
        assertEquals(token(firstHealth, "legacy_did"), token(secondHealth, "legacy_did"))
        assertEquals(token(firstHealth, "did"), panelAssistantDiscoveryId(config(firstStore).deviceUid))
        assertFalse(firstHealth.contains(clonedAndroidId))
        assertTrue(io.panelassistant.android.http.panelAssistantDiscoveryHealthToken(first.deviceUid).contains(" did="))
    }

    @Test fun failedIdentityWriteRetriesTheSameIdentityAndThenSurvivesRestart() {
        val store = RestoredState(emptyMap())
        store.durable = false
        val panel = config(store)
        val first = "a".repeat(32)
        assertTrue(runCatching { panel.ensureDeviceUid { first } }.isFailure)
        assertEquals("", io.panelassistant.android.http.panelAssistantDiscoveryHealthToken(panel.deviceUid))
        assertEquals("", config(store).deviceUid)
        assertTrue(runCatching { panel.ensureDeviceUid { "b".repeat(32) } }.isFailure)
        store.durable = true
        assertEquals(first, panel.ensureDeviceUid { "c".repeat(32) })
        assertEquals(first, config(store).deviceUid)
    }

    @Test fun signedHandoverAdoptsDurablyButCannotReplaceAnotherInstallation() {
        val store = RestoredState(emptyMap())
        val panel = config(store)
        store.durable = false
        assertFalse(panel.adoptMigrationDeviceUid("a".repeat(32)))
        assertEquals("", panel.deviceUid)
        store.durable = true
        assertTrue(panel.adoptMigrationDeviceUid("a".repeat(32)))
        val reopened = config(store)
        assertEquals("a".repeat(32), reopened.ensureDeviceUid())
        assertFalse(reopened.adoptMigrationDeviceUid("b".repeat(32)))
        assertEquals("a".repeat(32), reopened.deviceUid)
    }

    // --- a cloned ANDROID_ID across two simulated panels ------------------------------------------

    @Test fun twoPanelsSharingAnAndroidIdMintDistinctIdentities() {
        val first = panel()
        val second = panel()

        first.ensureDeviceUid()
        second.ensureDeviceUid()

        assertTrue("first panel minted nothing", first.deviceUid.isNotBlank())
        assertTrue("second panel minted nothing", second.deviceUid.isNotBlank())
        assertNotEquals(
            "two panels off one factory image must not share a device identity",
            first.deviceUid,
            second.deviceUid,
        )
    }

    @Test fun theMintedIdentityIsStableAcrossRestarts() {
        val store = RestoredState(emptyMap())
        val first = config(store)
        first.ensureDeviceUid()
        val minted = first.deviceUid

        val afterRestart = config(store)

        assertEquals("a new identity each boot would mint a new device each boot", minted, afterRestart.deviceUid)
        afterRestart.ensureDeviceUid()
        assertEquals("ensureDeviceUid must not re-mint over a persisted identity", minted, afterRestart.deviceUid)
    }

    // --- export and restore ------------------------------------------------------------------------

    @Test fun theMintedIdentityIsNotAnExportableSetting() {
        assertEquals(
            "a registered spec is exported, and an exported identity is a cloned identity",
            null,
            SettingsRegistry.spec(Config.DEVICE_UID_PREF),
        )
        assertTrue(
            "no registered setting may carry the device identity",
            SettingsRegistry.SPECS.none { it.key.contains("device_uid") },
        )
    }

    @Test fun anArchiveCarryingTheMintedIdentityIsRefusedRatherThanApplied() {
        val decision = PaneldServerAccess.planRestoreSettings(
            mapOf(Config.DEVICE_UID_PREF to "aaaa1111"),
            configuredOrigin = null,
        )

        assertEquals("a restore must never write a device identity", emptyMap<String, String>(), decision.accepted)
        assertTrue("and must say why", decision.errors.any { it.contains(Config.DEVICE_UID_PREF) })
    }

    @Test fun aRestoreOntoASecondPanelCannotCloneTheFirstPanelsIdentity() {
        val source = panel()
        source.ensureDeviceUid()

        // Everything an archive is allowed to carry, applied to a second panel.
        val target = panel()
        target.ensureDeviceUid()
        val restorable = PaneldServerAccess.planRestoreSettings(
            mapOf(Config.DEVICE_UID_PREF to source.deviceUid),
            configuredOrigin = null,
        ).accepted
        restorable.forEach { (key, value) -> error("unexpectedly restorable: $key=$value") }

        assertNotEquals(
            "restoring one panel's archive onto another must not clone its identity",
            source.deviceUid,
            target.deviceUid,
        )
    }

    // --- the generated panel id --------------------------------------------------------------------

    @Test fun twoFreshPanelsOffOneImageDoNotGenerateOneIdenticalPanelId() {
        val first = panelIdSuffix(clonedAndroidId, deviceUid = "aaaa1111")
        val second = panelIdSuffix(clonedAndroidId, deviceUid = "bbbb2222")

        assertNotEquals("identical panel ids mean one merged Home Assistant device", first, second)
        assertEquals("1111", first)
        assertEquals("2222", second)
    }

    @Test fun aPanelThatCanReadNeitherValueStillGetsAWellFormedSuffix() {
        assertEquals("panel", panelIdSuffix(androidId = "", deviceUid = ""))
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun panel(): Config = config(RestoredState(emptyMap()))

    private fun config(store: RestoredState): Config =
        Config(SqliteStatePreferences(store, writer))

    private class RestoredState(initial: Map<String, Any>) : StateNamespacePersistence {
        private val values = initial.toMutableMap()

        override fun initialize(): Map<String, Any> = values.toMap()

        var durable = true

        override fun persist(mutation: StateMutation): Boolean {
            if (!durable) return false
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            if (!durable) return false
            values.clear()
            values.putAll(snapshot)
            return true
        }
    }

    private companion object {
        private val writer = Executors.newSingleThreadExecutor()
    }
}

private object PaneldServerAccess {
    fun planRestoreSettings(migrated: Map<String, String>, configuredOrigin: String?) =
        io.panelassistant.android.http.planRestoreSettings(migrated, configuredOrigin)
}
