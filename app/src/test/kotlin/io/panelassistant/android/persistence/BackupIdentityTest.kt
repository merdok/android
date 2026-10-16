package io.panelassistant.android.persistence

import io.panelassistant.android.panelAssistantDiscoveryId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupIdentityTest {
    private val androidId = "9774d56d682e549c"
    private val discoveryId = panelAssistantDiscoveryId(androidId)!!

    private fun manifest(fragment: String) = JSONObject("{\"kind\":\"ha-paneld-backup\"$fragment}")

    @Test fun reinstallLinkSurvivesANewInstallationAndNeverFallsBackToAnEditableName() {
        val written = manifest(BackupIdentity.manifestFragment("a".repeat(64), "pkg", discoveryId))
        assertTrue(BackupIdentity.sameRestoreDevice(written, discoveryId, false))
        assertFalse(BackupIdentity.sameRestoreDevice(written, "b".repeat(64), true))
        assertFalse(BackupIdentity.sameRestoreDevice(written.put("legacy_did", "bad"), discoveryId, true))
        assertTrue(BackupIdentity.sameRestoreDevice(manifest(""), discoveryId, true))
        assertFalse(BackupIdentity.sameRestoreDevice(manifest(""), discoveryId, false))
        assertFalse(written.toString().contains(androidId))
    }

    @Test fun theManifestCarriesThePseudonymAndTheWritingPackage() {
        val written = manifest(BackupIdentity.manifestFragment(discoveryId, "io.github.maxlyth.hapaneld"))

        assertEquals(discoveryId, written.getString("discovery_id"))
        assertEquals("io.github.maxlyth.hapaneld", written.getString("package"))
    }

    @Test fun theRawAndroidIdIsNeverWritten() {
        val fragment = BackupIdentity.manifestFragment(discoveryId, "io.github.maxlyth.hapaneld")

        assertFalse(fragment.contains(androidId))
        // Anything that is not the 64-hex pseudonym, the Android id included, is dropped, not written.
        assertFalse(BackupIdentity.manifestFragment(androidId, "pkg").contains("discovery_id"))
        assertFalse(BackupIdentity.manifestFragment(null, "pkg").contains("discovery_id"))
    }

    @Test fun rawPreferencesCarryOnlyStringEntriesOfTheListedStores() {
        val fragment = RawPreferenceBackup.manifestFragment { store ->
            if (store == "proximity-wake-invalidation") mapOf("fp-b" to "tok2", "fp-a" to "tok1", "count" to 3)
            else error("unlisted store $store was read")
        }

        val section = manifest(fragment).getJSONObject("raw_preferences")
        assertEquals(setOf("proximity-wake-invalidation"), section.keys().asSequence().toSet())
        val store = section.getJSONObject("proximity-wake-invalidation")
        assertEquals(setOf("fp-a", "fp-b"), store.keys().asSequence().toSet())
        assertEquals("tok1", store.getString("fp-a"))
    }

    @Test fun anEmptyStoreWritesNoSection() {
        assertEquals("", RawPreferenceBackup.manifestFragment { emptyMap<String, Any>() })
        assertEquals(emptyMap<String, Map<String, String>>(), RawPreferenceBackup.restorable(manifest("")))
    }

    @Test fun aWrittenSectionRestoresExactly() {
        val fragment = RawPreferenceBackup.manifestFragment { mapOf("fp \"quoted\"" to "tok\n1") }

        assertEquals(
            mapOf("proximity-wake-invalidation" to mapOf("fp \"quoted\"" to "tok\n1")),
            RawPreferenceBackup.restorable(manifest(fragment)),
        )
    }

    @Test fun aSectionAWriterCouldNotHaveProducedIsMalformed() {
        fun section(body: String) = RawPreferenceBackup.restorable(manifest(",\"raw_preferences\":$body"))

        assertNull(section("[]"))
        assertNull(section("{\"ha-paneld\":{\"ui_language\":\"de\"}}"))
        assertNull(section("{\"proximity-wake-invalidation\":[]}"))
        assertNull(section("{\"proximity-wake-invalidation\":{\"k\":1}}"))
        assertNull(section("{\"proximity-wake-invalidation\":{\"\":\"v\"}}"))
        assertNull(section("{\"proximity-wake-invalidation\":{\"k\":\"${"v".repeat(1_025)}\"}}"))
        val tooMany = (0..64).joinToString(",") { "\"k$it\":\"v\"" }
        assertNull(section("{\"proximity-wake-invalidation\":{$tooMany}}"))
    }
}
