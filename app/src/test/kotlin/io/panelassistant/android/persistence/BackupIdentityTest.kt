package io.panelassistant.android.persistence

import io.panelassistant.android.panelAssistantDiscoveryId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
