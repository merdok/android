// Run PicklesPageFixtureTest first: these are real guarded page documents, with the shipped scripts.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { attachUiSocket, loopbackAnswer } from './fixtures/ui-socket.mjs';

const assets = join(process.cwd(), '../../app/src/main/assets');
const fixtures = join(process.cwd(), '../../app/build/test-fixtures/pickles');
const locales = ['en', 'de', 'fr', 'it', 'es', 'zh-Hans', 'nl', 'pl', 'uk'];
const endpoints = { configure: '/api/v1/config', dashboard: '/api/v1/info', api: '/api/v1/openapi.json', profiles: '/api/v1/profiles' };
const names = { configure: 'config', dashboard: 'info', api: 'openapi.json', profiles: 'profiles' };
const schema = [{ key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', tier: 'BASIC', available: true, help: 'Touch sound' }];

async function harness(surface, locale, { fail = true, failurePath = endpoints[surface] } = {}) {
  let reads = 0, infoReads = 0;
  const html = await readFile(join(fixtures, `${surface}-${locale}.html`), 'utf8');
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const json = (data, status = 200) => { response.writeHead(status, { 'content-type': 'application/json' }); response.end(JSON.stringify(data)); };
    if (path === '/page') { reads++; response.setHeader('content-type', 'text/html'); response.end(html); return; }
    if (path === '/api/v1/info') infoReads++;
    if (fail && path === failurePath) { json({ message: '<script>{x}</script> load unavailable' }, 503); return; }
    if (path === '/api/v1/config/schema') { json(schema); return; }
    if (path === '/api/v1/config') { json({ settings: { touch_sound: 'false' }, ha_expose: {}, ha_auth: {} }); return; }
    if (path === '/api/v1/info') { json({ banners: '', cards: { infotbl: '<tr><td>Ready</td><td>loaded</td></tr>' }, controls: '', shot: false }); return; }
    if (path === '/api/v1/openapi.json') { json({ openapi: '3.0.3', info: { title: 'Test', version: '1' }, paths: { '/api/v1/probe': { get: { summary: 'Probe', responses: { '200': { description: 'OK' } } } } } }); return; }
    if (path === '/api/v1/profiles') { json({ catalog_revision: 1, profiles: [], status: {} }); return; }
    if (path.startsWith('/api/')) { json({ apps: [], renderers: [], present: false, hist: { cpu: [], ram: [], gpu: [] } }); return; }
    const relative = path.startsWith('/assets/') ? path.slice(8) : path.slice(1);
    try {
      const body = await readFile(join(assets, relative));
      response.setHeader('content-type', relative.endsWith('.css') ? 'text/css' : relative.endsWith('.svg') ? 'image/svg+xml' : 'application/javascript');
      response.end(body);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  // The pages are the production documents, so outside the sidebar they talk over the stub's /ui socket: the
  // fixture door answers each request from the routes above, as the panel's door answers from its handlers.
  await attachUiSocket(server, loopbackAnswer(server), { record: null });
  return { url: `http://127.0.0.1:${server.address().port}/page`, recover: () => { fail = false; }, reads: () => reads, infoReads: () => infoReads, close: () => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }) };
}

for (const [engine, type, launch] of [
  ['Chromium', chromium, { executablePath: process.env.CHROME || '/usr/bin/chromium', args: ['--no-sandbox'] }],
  ['WebKit', webkit, {}],
]) {
  test(`production initial-load failures and 404 show Pickles safely at 480px in ${engine}`, async (t) => {
    let maxActionBottom = 0;
    const browser = await type.launch(launch);
    try {
      for (const surface of ['configure', 'dashboard', 'api', 'profiles', '404']) {
        for (const locale of locales) for (const theme of ['light', 'dark']) {
          const rig = await harness(surface, locale);
          const page = await browser.newPage({ viewport: { width: 480, height: 480 }, colorScheme: theme });
          const errors = []; page.on('pageerror', error => errors.push(error.message));
          // Exhaust the existing bounded cold-hydration retries without a 30-second wait per locale.
          await page.addInitScript(() => { const timeout = window.setTimeout; window.setTimeout = (fn, ms, ...args) => timeout(fn, ms === 3000 ? 0 : ms, ...args); });
          try {
            await page.goto(`${rig.url}?theme=${theme}`);
            await page.locator('main.pickles').waitFor();
            await page.locator('main.pickles img').evaluate(image => image.decode());
            const result = await page.evaluate(() => {
              const main = document.querySelector('main.pickles');
              return { actionBottom: main.querySelector('button,a').getBoundingClientRect().bottom, width: document.documentElement.scrollWidth, bg: getComputedStyle(document.body).backgroundColor, diagnostic: main.querySelector('code').textContent, injected: main.querySelectorAll('code script,code img').length };
            });
            maxActionBottom = Math.max(maxActionBottom, result.actionBottom);
            assert.equal(result.injected, 0);
            assert.ok(result.width <= 480, `${surface}/${locale}/${theme}: ${JSON.stringify(result)}`);
            assert.ok(result.actionBottom <= 480, `${surface}/${locale}/${theme}: ${JSON.stringify(result)}`);
            assert.equal(result.bg, theme === 'dark' ? 'rgb(17, 17, 17)' : 'rgb(242, 243, 245)');
            if (surface !== '404') {
              // The diagnostic names the failed read and its outcome code (a 503 is hardware_unavailable).
              assert.ok(result.diagnostic.includes(names[surface]), `${surface}: ${result.diagnostic}`);
              assert.ok(result.diagnostic.includes(surface === 'profiles' ? '<script>{x}</script>' : 'hardware_unavailable'), `${surface}: ${result.diagnostic}`);
              assert.equal(await page.locator('main.pickles code').getAttribute('lang'), 'und');
              if (locale === 'en') {
                rig.recover();
                await Promise.all([page.waitForNavigation(), page.locator('main.pickles button').click()]);
                assert.ok(rig.reads() >= 2, 'Retry reloads the current page');
                if (surface === 'configure') await page.locator('#cfg-touch_sound').waitFor();
                if (surface === 'dashboard') await page.locator('#infotbl').filter({ hasText: 'loaded' }).waitFor();
                if (surface === 'api') await page.locator('#root details').waitFor();
                if (surface === 'profiles') await page.locator('#profile-status').filter({ hasText: 'No profiles are available.' }).waitFor();
                assert.equal(await page.locator('main.pickles').count(), 0, 'successful initial load retains page');
              }
            }
            assert.deepEqual(errors, [], `${surface}/${locale}/${theme}`);
          } finally { await page.close(); await rig.close(); }
        }
      }
    } finally { await browser.close(); }
    t.diagnostic(`90 failure samples; maximum action bottom ${maxActionBottom}px`);
  });

  test(`usable stale Dashboard and optional Configure data survive failed reads in ${engine}`, async () => {
    const browser = await type.launch(launch);
    try {
      for (const [surface, failurePath] of [['dashboard-stale', '/api/v1/info'], ['configure', '/api/v1/apps'], ['configure', '/api/v1/proximity']]) {
        const rig = await harness(surface, 'en', { failurePath });
        const page = await browser.newPage();
        const errors = []; page.on('pageerror', error => errors.push(error.message));
        if (surface === 'dashboard-stale') await page.addInitScript(() => {
          const timeout = window.setTimeout; window.setTimeout = (fn, ms, ...args) => timeout(fn, ms === 3000 ? 0 : ms, ...args);
        });
        try {
          await page.goto(rig.url);
          if (surface === 'dashboard-stale') {
            const deadline = Date.now() + 30000;
            while (rig.infoReads() < 11 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
            assert.equal(rig.infoReads(), 11, 'the bounded cold-hydration retries all ran');
            await page.waitForTimeout(200);
          }
          else await page.locator('#cfg-touch_sound').waitFor();
          assert.equal(await page.locator('main.pickles').count(), 0);
          if (surface === 'dashboard-stale') assert.match(await page.locator('#infotbl').textContent(), /Warm <panel>/);
          assert.deepEqual(errors, []);
        } finally { await page.close(); await rig.close(); }
      }
    } finally { await browser.close(); }
  });
}
