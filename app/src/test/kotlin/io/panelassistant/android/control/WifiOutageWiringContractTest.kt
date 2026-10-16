package io.panelassistant.android.control

import io.panelassistant.android.testsupport.TestSources
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOutageWiringContractTest {
    // Source-text reason: the diagnostic channels need the Android service to construct.
    private val core = source("PanelStateCore.kt")

    @Test fun outageCountersKeepReportingThroughTheDropoutTheyReport() {
        // WIFI_DIAGNOSTIC_KEYS members go unavailable when Wi-Fi goes away — the opposite of what a
        // dropout counter wants. The counters must stay out of that set.
        // Bounded at the set literal's closing paren, not the first newline, so the guard keeps
        // scanning the whole declaration if it is ever reformatted across lines.
        val keys = core.substring(
            core.indexOf("internal val WIFI_DIAGNOSTIC_KEYS"),
            core.indexOf(")", core.indexOf("internal val WIFI_DIAGNOSTIC_KEYS")),
        )
        assertFalse(keys.contains("diag_wifi_outages_24h"))
        assertFalse(keys.contains("diag_wifi_outages_7d"))
    }

    @Test fun theCounterReadsTheTrackerSnapshot() {
        assertTrue(core.contains(""""diag_wifi_outages_24h" -> wifiOutages()?.last24h?.toString()"""))
        // Membership in DIAG_KEYS is what wires discovery and the heartbeat refresh; a diagValue
        // branch alone is unreachable code.
        val diagKeys = core.substring(
            core.indexOf("internal val DIAG_KEYS"),
            core.indexOf(")", core.indexOf("internal val DIAG_KEYS")),
        )
        assertTrue(diagKeys.contains("\"diag_wifi_outages_24h\""))
    }

    private fun source(relative: String): String =
        (TestSources.appFileOrNull("src/main/kotlin/io/panelassistant/android/$relative")
            ?: TestSources.kotlin("control/$relative")).readText()
}
