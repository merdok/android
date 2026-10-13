package io.panelassistant.android.testsupport

/**
 * The real translation bridge and page transport for node-driven asset tests, loaded the way the page
 * shell loads them before every page script: assets/i18n.js with no ha-i18n payload, so every lookup
 * answers its English fallback, then assets/transport.js, whose HTTP implementation drives the script's
 * own fake `fetch`. Paste it into a node script after `window` (and any `document`) exists and before
 * the page script runs; the script must have `vm` in scope and `process.argv[1]` pointing at a file in
 * assets/. Browser and .mjs fixtures use tools/test/fixtures/i18n-bridge.mjs instead.
 */
object NodeI18n {
    // Source-text reason: executes the shipped i18n.js and transport.js in node, as the page shell loads them.
    /** app/src/test/js/complete-fake-responses.cjs, found from the asset the script runs (src/main/assets). */
    private const val COMPLETE_FAKE_RESPONSES: String =
        "require(require('path').join(require('path').dirname(process.argv[1]),'..','..','test','js','complete-fake-responses.cjs'));"

    const val REAL_BRIDGE: String =
        "(()=>{const d=global.document;global.document={getElementById:()=>null};" +
            "vm.runInThisContext(require('fs').readFileSync(require('path').join(require('path').dirname(process.argv[1]),'i18n.js'),'utf8'));" +
            "global.document=d===undefined?{}:d;" +
            "vm.runInThisContext(require('fs').readFileSync(require('path').join(require('path').dirname(process.argv[1]),'transport.js'),'utf8'));" +
            "if(d===undefined)delete global.document;})();" + COMPLETE_FAKE_RESPONSES
}
