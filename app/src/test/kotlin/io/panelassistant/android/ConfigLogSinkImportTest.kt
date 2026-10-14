package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigLogSinkImportTest {
    // ---- log-sink address consistency (0.9.7) ----------------------------------------------------

    private val desyncPrefs = mapOf(
        "log_ship_host" to "old.lan",
        "log_ship_port" to 514,
        "log_ship_protocol" to "syslog-tcp",
    )

    @Test fun anImportedEmbeddedSinkAddressRewritesAllThreeFieldsInOneTransaction() {
        // Without the dependency pass the host is stored verbatim and Port/Protocol keep describing
        // somewhere else, so the panel ships to one destination while every surface reports another.
        val prefs = configTestPreferences(initial = desyncPrefs)
        val config = Config(prefs.instance)
        val editor = config.editor()

        stageImport(config, editor, mapOf("log_ship_host" to "udp://collector.lan:1514"))
        assertTrue(config.commit(editor))

        assertEquals("collector.lan", prefs.values["log_ship_host"])
        assertEquals(1514, prefs.values["log_ship_port"])
        assertEquals("syslog-udp", prefs.values["log_ship_protocol"])
        assertEquals("collector.lan", config.logShipHost)
        assertEquals(1514, config.logShipPort)
        assertEquals("syslog-udp", config.logShipProtocol)
    }

    @Test fun theStagedSinkTripleDoesNotDependOnAcceptedIterationOrder() {
        // Same three entries in both directions; the committed destination must be identical.
        val entries = listOf(
            "log_ship_host" to "udp://collector.lan:1514",
            "log_ship_port" to "514",
            "log_ship_protocol" to "syslog-tcp",
        )
        val committed = listOf(entries, entries.reversed()).map { ordering ->
            val prefs = configTestPreferences(initial = desyncPrefs)
            val config = Config(prefs.instance)
            val editor = config.editor()
            stageImport(config, editor, linkedMapOf(*ordering.toTypedArray()))
            assertTrue(config.commit(editor))
            Triple(
                prefs.values["log_ship_host"], prefs.values["log_ship_port"], prefs.values["log_ship_protocol"],
            )
        }
        assertEquals(committed[0], committed[1])
        assertEquals(Triple("collector.lan", 1514, "syslog-udp"), committed[0])
    }

    @Test fun aFailedCommitLeavesTheSinkTripleAtItsPreviousConsistentDestination() {
        // Partial admission must recover atomically: the three fields are staged into one editor, so a
        // failed commit writes none of them rather than leaving a host that disagrees with its port.
        val prefs = configTestPreferences(initial = desyncPrefs, commitSucceeds = false)
        val config = Config(prefs.instance)
        val editor = config.editor()

        stageImport(config, editor, mapOf("log_ship_host" to "udp://collector.lan:1514"))
        assertFalse(config.commit(editor))

        assertEquals("old.lan", prefs.values["log_ship_host"])
        assertEquals(514, prefs.values["log_ship_port"])
        assertEquals("syslog-tcp", prefs.values["log_ship_protocol"])
        assertEquals("old.lan", config.logShipHost)
        assertEquals(514, config.logShipPort)
        assertEquals("syslog-tcp", config.logShipProtocol)
    }
}
