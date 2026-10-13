'use strict';
// Fake `fetch` answers written for page code that read only `json()` get the rest of a Response's shape the
// real page transport reads: the body from `json()` when the fake has one (its own `text()` only when that
// rejects; an empty body when it has neither), `status` from `ok` (200 unless `ok` is false), and empty
// `headers`. Applies to whatever `fetch` the test installs, before or after this file loads. Used by the
// node asset tests (NodeI18n.REAL_BRIDGE) and the .mjs harnesses (page-transport.mjs) alike.
let raw = global.fetch;
const full = r => (!r || typeof r !== 'object' || typeof r.then === 'function') ? r : {
  ok: r.ok === undefined ? (r.status === undefined || (r.status >= 200 && r.status < 300)) : r.ok,
  status: r.status === undefined ? (r.ok === false ? 500 : 200) : r.status,
  headers: r.headers || { get: () => null, forEach() {} },
  json: r.json ? r.json.bind(r) : () => r.text().then(JSON.parse),
  text: r.json
    ? () => r.json().then(v => typeof v === 'string' ? v : JSON.stringify(v), e => r.text ? r.text() : Promise.reject(e))
    : r.text ? r.text.bind(r) : () => Promise.resolve(''),
  blob: r.blob ? r.blob.bind(r) : undefined,
  clone() { return full(r.clone ? r.clone() : r); },
};
Object.defineProperty(global, 'fetch', {
  configurable: true,
  get() { return raw && ((...a) => Promise.resolve(raw(...a)).then(full)); },
  set(f) { raw = f; },
});
