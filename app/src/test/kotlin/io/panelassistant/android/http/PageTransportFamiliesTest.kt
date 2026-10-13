package io.panelassistant.android.http

import io.panelassistant.android.ManageFamilies
import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription families exist twice: the panel's registry (ManageFamilies), which the socket door and the
 * session serve, and the page transport's table (assets/transport.js), which its HTTP implementation polls
 * with. A page reads the same value on both transports only while the two agree on names, reads and floors.
 */
class PageTransportFamiliesTest {
    // Source-text reason: the family table in transport.js is page code the JVM cannot load; it is read as data.
    private val table by lazy {
        TestSources.asset("transport.js").readText().substringAfter("var FAMILIES = {").substringBefore("\n  };")
    }
    private val row = Regex("""(\w+): \{ floor: (\d+), reads: \[([^\]]*)\](, append: true)? \}""")

    @Test fun `the page transport's families are the panel's families`() {
        val page = row.findAll(table).associate { match ->
            val reads = Regex(""""([^"]+)"""").findAll(match.groupValues[3]).map { it.groupValues[1] }.toList()
            match.groupValues[1] to Triple(match.groupValues[2].toLong(), reads, match.groupValues[4].isNotEmpty())
        }
        assertTrue("the family table parsed", page.isNotEmpty())
        val panel = ManageFamilies.DEFAULT.mapValues { (_, family) -> Triple(family.floorMs, family.reads, family.append) }

        assertEquals(panel, page)
    }
}
