// The Entities lists re-read their rows when the entities family's rows change (row rates move between scans),
// as the old 10 s poll did, and not when nothing changed.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import { chromium } from 'playwright-core';

const chrome = process.env.CHROME || '/usr/bin/chromium';
const entitiesSource = fs.readFileSync('../../app/src/main/assets/entities.js', 'utf8');
const i18nSource = fs.readFileSync('../../app/src/main/assets/i18n.js', 'utf8');
const transportSource = fs.readFileSync('../../app/src/main/assets/transport.js', 'utf8');

function table(id, filter) {
  return `<section class="entity-list" data-table="${id}" data-filter="${filter}">
    <table><thead><tr><th><button data-sort="entity_id">Entity</button></th></tr></thead><tbody></tbody></table>
    <button class="entity-prev"></button><button class="entity-next"></button>
    <input class="entity-select-page" type="checkbox"><span class="entity-selected"></span><span class="entity-msg"></span>
    <button data-bulk="pinned"></button><button data-bulk="auto"></button><button data-bulk="forced_exclude"></button>
  </section>`;
}

function html() {
  const status = { state: 'observing', sync_running: false, stream_entity_count: 1, stream_mode: 'filtered', catalog_count: 1, suggested_count: 0, blocking_issue_count: 0, ignored_issue_count: 0, unresolved_count: 0, last_sync_at: 0, db_bytes: 8, auto_static: true, auto_runtime: true, apply_required: false };
  const bootstrap = `window.__polls=[];window.__lists=0;window.__rate=1;window.__setTimeout=window.setTimeout;window.setTimeout=function(fn,ms){if(ms===5000){window.__polls.push(fn);return 0}return window.__setTimeout.apply(window,arguments)};
    window.fetch=async(url)=>{url=String(url);var body;if(url.includes('/entities/issues'))body={items:[],dashboard_issue_count:0,blocking_issue_count:0,ignored_issue_count:0,dynamic_expressions:[]};else if(url.includes('/entities?')&&url.includes('filter=')){window.__lists++;body={items:[],total:0}}else if(/dashboard\\/entities(\\?|$)/.test(url))body={items:[{entity_id:'sensor.a',rate:window.__rate}],total:1};else body=${JSON.stringify(status)};return new Response(JSON.stringify(body),{status:200,headers:{'content-type':'application/json'}})};`;
  return `<!doctype html><html lang="en"><body>
    <script id="ha-i18n" type="application/json">{"locale":"en","strings":{},"languages":{}}</script>
    <script>${i18nSource.replaceAll('</script>', '<\\/script>')}</script><script>${bootstrap.replaceAll('</script>', '<\\/script>')}</script><script>${transportSource.replaceAll('</script>', '<\\/script>')}</script>
    <div id="entity-status"></div><input id="entity-search"><div id="entity-search-status"></div>
    <button id="entity-sync"></button><button id="entity-activate"></button><button id="entity-reset"></button><div id="entity-action-result"></div>
    <input id="entity-auto-static" type="checkbox"><input id="entity-auto-runtime" type="checkbox">
    <section id="entity-issues"><div id="entity-issues-summary"></div><div id="entity-issues-list"></div><button id="entity-issues-rescan"></button></section>
    <section id="entity-dynamic" hidden><div id="entity-dynamic-list"></div></section>
    ${table('current', 'subscribed')}${table('suggested', 'candidate')}
    <script>${entitiesSource.replaceAll('</script>', '<\\/script>')}</script></body></html>`;
}

test('Entities re-reads its lists when the family rows change and not otherwise', { skip: !fs.existsSync(chrome), timeout: 20_000 }, async () => {
  const browser = await chromium.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    await page.setContent(html(), { waitUntil: 'load' });
    await page.waitForFunction(() => window.__polls.length === 1 && window.__lists === 2);
    const poll = () => page.evaluate(async () => { const next = window.__polls.length; window.__polls[next - 1](); await new Promise((resolve) => { const wait = () => (window.__polls.length > next ? resolve() : window.__setTimeout(wait, 5)); wait(); }); });

    await poll();
    await page.waitForTimeout(50);
    assert.equal(await page.evaluate(() => window.__lists), 2, 'unchanged rows re-read nothing');

    await page.evaluate(() => { window.__rate = 2; });
    await poll();
    await page.waitForFunction(() => window.__lists === 4, null, { timeout: 5_000 });
  } finally {
    await browser.close();
  }
});
