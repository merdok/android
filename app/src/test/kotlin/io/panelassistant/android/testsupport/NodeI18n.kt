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
    /**
     * Fake `fetch` answers written for page code that read only `json()` get the rest of a Response's shape
     * the real transport reads: `text()` from the JSON, `status` from `ok` (200 unless `ok` is false), and
     * empty `headers`. Applies to whatever `fetch` the script installs, before or after this line.
     */
    private const val COMPLETE_FAKE_RESPONSES: String =
        "(()=>{let raw=global.fetch;const full=r=>(!r||typeof r!=='object'||typeof r.then==='function')?r:{" +
            "ok:r.ok===undefined?(r.status===undefined||(r.status>=200&&r.status<300)):r.ok," +
            "status:r.status===undefined?(r.ok===false?500:200):r.status," +
            "headers:r.headers||{get:()=>null,forEach(){}}," +
            "json:r.json?r.json.bind(r):()=>r.text().then(JSON.parse)," +
            "text:r.text?r.text.bind(r):()=>r.json().then(v=>typeof v==='string'?v:JSON.stringify(v))," +
            "blob:r.blob?r.blob.bind(r):undefined,clone(){return full(r.clone?r.clone():r)}};" +
            "Object.defineProperty(global,'fetch',{configurable:true,get(){return raw&&((...a)=>Promise.resolve(raw(...a)).then(full))},set(f){raw=f}});})();"

    const val REAL_BRIDGE: String =
        "(()=>{const d=global.document;global.document={getElementById:()=>null};" +
            "vm.runInThisContext(require('fs').readFileSync(require('path').join(require('path').dirname(process.argv[1]),'i18n.js'),'utf8'));" +
            "global.document=d===undefined?{}:d;" +
            "vm.runInThisContext(require('fs').readFileSync(require('path').join(require('path').dirname(process.argv[1]),'transport.js'),'utf8'));" +
            "if(d===undefined)delete global.document;})();" + COMPLETE_FAKE_RESPONSES
}
