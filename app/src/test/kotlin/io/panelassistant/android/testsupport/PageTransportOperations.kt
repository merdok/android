package io.panelassistant.android.testsupport

/**
 * The operation names the page transport routes (the ROUTES table of assets/transport.js). Page code passes
 * them to `request(...)` as names such as `profiles.select` or `setup.identity`; they share a prefix with
 * catalogue keys but are never translated, so a literal-key scan leaves them out.
 */
object PageTransportOperations {
    // Source-text reason: the route table in transport.js is the one definition of the page's operation names.
    val NAMES: Set<String> by lazy {
        val table = TestSources.asset("transport.js").readText().substringAfter("var ROUTES = {").substringBefore("\n  };")
        Regex(""""([a-z][a-z0-9_.]*)"\s*:""").findAll(table).mapTo(sortedSetOf()) { it.groupValues[1] }
    }
}
