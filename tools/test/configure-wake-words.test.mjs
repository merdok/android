// The Configure wake-word rows: the picker, the import of a model the owner trained, and the download of a
// community model from the app's pinned list, whose author and licence are shown before anything is fetched.
import assert from 'node:assert/strict';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';
import { browserTest, chrome, deferred, json, startHarness as startHarnessWith } from './fixtures/browser-harness.mjs';

// These journeys exercise every setting, advanced ones included, so their Configure stubs open in the
// Advanced view the way a returning user's browser would; configure-density.test.mjs covers Basic.
const HELP_POPOVER = '<div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>';
const ADVANCED_VIEW = `<script>try{localStorage.getItem('ha-paneld.configure.view.v1')||localStorage.setItem('ha-paneld.configure.view.v1','{"advanced":true}')}catch(e){}</script>`;


function fixture(translations = {}, locale = 'en') {
  return `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css">${i18nBridge({ locale, strings: translations })}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn" onclick="cfgSave()"></button></div>${HELP_POPOVER}
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

const startHarness = (routes) => startHarnessWith(routes, fixture);

async function requestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

browserTest('Configure offers the wake words as checkboxes and imports a trained one from its own row', async (t) => {
  // The import takes a model the owner trained, so the way to train one has to be one tap away from it.
  // It spans the card rather than sharing the wake-word row's control column.
  let imported = null;
  const schema = [
    { key: 'voice_enabled', label: 'Voice assistant', group: 'Voice', type: 'BOOL', available: true },
    { key: 'voice_wake_words', label: 'Wake words', group: 'Voice', type: 'STRING', picker: 'voice_wake_words', available: true },
  ];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') return json({});
      return json({ settings: { voice_enabled: 'true', voice_wake_words: '["hey_jarvis"]' }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/voice/wake-words') {
      if (request.method === 'POST') {
        imported = JSON.parse(await requestBody(request));
        return json({ id: 'hey_panel', wake_word: 'Hey Panel' });
      }
      return json({ wake_words: [
        { id: 'okay_nabu', wake_word: 'Okay Nabu', imported: false },
        { id: 'hey_jarvis', wake_word: 'Hey Jarvis', imported: false },
      ] });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/voice/pipelines') return json({ pipelines: [] });
    if (path === '/health') return { body: 'ok cfg=test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const rows = page.locator('.voice-wake-word-row');
  await assert.doesNotReject(rows.nth(1).waitFor());
  assert.deepEqual(await rows.allTextContents(), ['Okay Nabu', 'Hey Jarvis']);
  assert.equal(await rows.nth(1).locator('input').isChecked(), true);
  assert.equal(await rows.nth(0).locator('input').isChecked(), false);

  const guide = page.locator('.voice-wake-word-import a.voice-wake-word-guide');
  assert.equal(await guide.count(), 1);
  assert.equal(await guide.getAttribute('href'), 'https://panel-assistant.io/go/custom-wake-words');
  assert.equal(await guide.getAttribute('target'), '_blank');
  assert.equal(await guide.textContent(), 'How to train your own wake word');
  assert.equal(await page.locator('.voice-wake-words-picker .voice-wake-word-import').count(), 0);
  assert.equal(await page.locator('[data-config-group="Voice"] > .voice-wake-word-import').count(), 1);

  await page.locator('.voice-wake-word-import input[type="file"]').setInputFiles([
    { name: 'hey_panel.tflite', mimeType: 'application/octet-stream', buffer: Buffer.from([1, 2, 3]) },
    { name: 'hey_panel.json', mimeType: 'application/json', buffer: Buffer.from('{"wake_word":"Hey Panel"}') },
  ]);
  await page.locator('.voice-wake-word-import button').click();
  await assert.doesNotReject(page.locator('.voice-wake-word-import-status', { hasText: 'Imported Hey Panel.' }).waitFor());
  assert.deepEqual(imported, { name: 'hey_panel.tflite', manifest: '{"wake_word":"Hey Panel"}', model: 'AQID' });
});

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Configure shows a community wake word's author and licence before downloading it in ${engine}`, async (t) => {
    // Nothing is fetched until the owner has seen who made the picked model and under which licence;
    // once the panel holds it, it is no longer offered. A download in flight cannot be started twice, and
    // the result of downloading the last offered model is still shown.
    const posts = [];
    const held = new Set();
    let reply = Promise.resolve();
    const words = { hey_donut: 'Hey Donut', nocturna: 'Nocturna' };
    const schema = [
      { key: 'voice_enabled', label: 'Voice assistant', group: 'Voice', type: 'BOOL', available: true },
      { key: 'voice_wake_words', label: 'Wake words', group: 'Voice', type: 'STRING', picker: 'voice_wake_words', available: true },
    ];
    const download = (id, wakeWord, author, license, installed) => ({
      id, wake_word: wakeWord, author, license, installed, bytes: 63496,
      license_url: `https://github.com/${author}/models/blob/0123456789abcdef0123456789abcdef01234567/LICENSE`,
      source: `https://github.com/${author}/models`,
    });
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') return json(schema);
      if (path === '/api/v1/config') {
        if (request.method === 'POST') return json({});
        return json({ settings: { voice_enabled: 'true', voice_wake_words: '["okay_nabu"]' }, ha_expose: {}, ha_auth: {} });
      }
      if (path === '/api/v1/voice/wake-words') {
        if (request.method === 'POST') {
          const body = JSON.parse(await requestBody(request));
          posts.push(body);
          await reply;
          held.add(body.download);
          return json({ id: body.download, wake_word: words[body.download] });
        }
        return json({
          wake_words: [{ id: 'okay_nabu', wake_word: 'Okay Nabu', imported: false }]
            .concat([...held].map((id) => ({ id, wake_word: words[id], imported: true }))),
          downloads: [
            download('hey_donut', 'Hey Donut', 'nyc-esq', 'CC0-1.0', held.has('hey_donut')),
            download('nocturna', 'Nocturna', 'MorningstarOwl', 'Apache-2.0', held.has('nocturna')),
          ],
        });
      }
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio') return json({ present: false });
      if (path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/voice/pipelines') return json({ pipelines: [] });
      if (path === '/health') return { body: 'ok cfg=test' };
    });
    const browser = await launcher.launch({ executablePath: engine === 'Chromium' ? chrome : undefined, headless: true });
    const page = await browser.newPage({ viewport: { width: 360, height: 740 } });
    page.setDefaultTimeout(1_500);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

    const row = page.locator('[data-config-group="Voice"] > .voice-wake-word-download');
    await assert.doesNotReject(row.waitFor());
    assert.deepEqual(await row.locator('option').allTextContents(), ['Hey Donut', 'Nocturna']);
    assert.equal(await row.locator('.voice-wake-word-download-terms').textContent(), 'By nyc-esq · Licence: CC0-1.0');

    await row.locator('select').selectOption('nocturna');
    const terms = page.locator('.voice-wake-word-download-terms');
    await assert.doesNotReject(page.locator('.voice-wake-word-download-terms', { hasText: 'MorningstarOwl' }).waitFor());
    assert.equal(await terms.textContent(), 'By MorningstarOwl · Licence: Apache-2.0');
    assert.equal(await terms.locator('a').getAttribute('href'),
      'https://github.com/MorningstarOwl/models/blob/0123456789abcdef0123456789abcdef01234567/LICENSE');
    assert.equal(posts.length, 0);
    const fits = await page.locator('.voice-wake-word-download').evaluate((node) => node.scrollWidth <= node.clientWidth + 1);
    assert.equal(fits, true);

    const slow = deferred();
    reply = slow.promise;
    await page.locator('.voice-wake-word-download button').click();
    await assert.doesNotReject(page.locator('.voice-wake-word-download-status', { hasText: 'Downloading Nocturna…' }).waitFor());
    await page.locator('.voice-wake-word-download select').selectOption('hey_donut');
    await assert.doesNotReject(page.locator('.voice-wake-word-download-terms', { hasText: 'nyc-esq' }).waitFor());
    assert.equal(await page.locator('.voice-wake-word-download button').isDisabled(), true);
    await page.locator('.voice-wake-word-download button').click({ force: true });
    slow.resolve();
    await assert.doesNotReject(page.locator('.voice-wake-word-download-status', { hasText: 'Downloaded Nocturna.' }).waitFor());
    assert.deepEqual(posts, [{ download: 'nocturna' }]);
    assert.deepEqual(await page.locator('.voice-wake-word-download option').allTextContents(), ['Hey Donut']);
    await assert.doesNotReject(page.locator('.voice-wake-word-row', { hasText: 'Nocturna' }).waitFor());

    await page.locator('.voice-wake-word-download button').click();
    await assert.doesNotReject(page.locator('.voice-wake-word-download-status', { hasText: 'Downloaded Hey Donut.' }).waitFor());
    assert.equal(await page.locator('.voice-wake-word-download select').count(), 0);
    assert.deepEqual(posts, [{ download: 'nocturna' }, { download: 'hey_donut' }]);
  });
}
