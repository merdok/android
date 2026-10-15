package io.panelassistant.android.assist.wakeword

import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list the app ships: every entry the panel can offer, each pinned and carrying a licence.
 * Source-text reason: the list is data the panel reads at run time, so this runs the shipped file through
 * the production parser; an entry it drops would vanish from Configure without any other test noticing.
 */
class DownloadableWakeWordListTest {
    private val json = TestSources.asset(WakeWordCatalog.DOWNLOADS_ASSET).readText()
    private val bundledIds = TestSources.asset("${MicroWakeWordModelConfig.ASSET_DIR}/okay_nabu.json").parentFile.list()!!
        .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }

    @Test fun `every shipped entry is offered, none shadows a bundled wake word, and each is pinned to a commit`() {
        val raw = JSONObject(json).getJSONArray("models").length()
        val offered = DownloadableWakeWord.parseAll(json)

        assertEquals("an entry the parser drops would silently vanish from the panel", raw, offered.size)
        assertTrue(offered.isNotEmpty())
        val commit = Regex("/[0-9a-f]{40}/")
        for (entry in offered) {
            assertTrue(entry.id, entry.id !in bundledIds)
            assertTrue(entry.id, commit.containsMatchIn(entry.manifestUrl) && commit.containsMatchIn(entry.licenseUrl))
            assertTrue(entry.id, entry.modelBytes <= 2L * 1024 * 1024)
        }
    }
}
