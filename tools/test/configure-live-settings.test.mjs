// Live settings on the Configure page, in both engines at phone width. There is no Save button: each
// finished gesture sends one `settings` op for its key, and changes made anywhere else (a Panel Assistant
// entity, another browser) reach the open page through the `config` family within a second. While a
// key's send is scheduled or in flight it ignores pushed values; a refused send puts the panel's value
// back and says why on the row; a setting the dashboard does not show yet says so.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const SCRIPTS = ['configure-state.js', 'configure-view.js', 'configure-help.js', 'configure-controls.js', 'configure-brightness.js',
  'configure-auto-sleep.js', 'configure-cards.js', 'configure-render.js', 'configure.js'];

// The production shell's Configure markup (ConfigurePage.kt): no save bar; #cfg-msg is a live region.
function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-conditional-description"></span>
    <div id="cfg-status" class="muted"></div><div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>
    <div id="cfg-all-cards"><div id="cfg-groups" class="cards"></div></div>
    <div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${SCRIPTS.map((name) => `<script src="/${name}"></script>`).join('')}
  </body></html>`;
}

const field = (key, label, group, extra = {}) => ({ key, label, group, type: 'BOOL', tier: 'BASIC', available: true, ...extra });
const SCHEMA = [
  field('dashboard_fullscreen', 'Fullscreen', 'Dashboard'),
  field('dashboard_theme', 'Theme', 'Dashboard', { type: 'ENUM', options: ['auto', 'light', 'dark'] }),
  field('panel_id', 'Panel ID', 'Identity', { type: 'STRING', maxLength: 63 }),
  field('camera_fps', 'Frame rate', 'Camera', { type: 'INT', min: 1, max: 30, step: 1 }),
  field('camera_kbps', 'Bitrate (kbps)', 'Camera', { type: 'INT', min: 250, max: 8000, step: 250 }),
];

// A fake panel. `state` is what Home Assistant (or anyone) last left on it; the test changes it directly to
// stand for a change made elsewhere. `onPost` decides each settings write; by default it applies.
async function harness({ onPost } = {}) {
  const state = {
    settings: { dashboard_fullscreen: false, dashboard_theme: 'auto', panel_id: 'office_panel', camera_fps: 15, camera_kbps: 2000 },
    ha_expose: {}, ha_auth: { configured: false }, apply_pending: {}, apply_stalled: [], renderer_pending: [],
  };
  const posts = [];
  let documents = 0;
  const apply = async (form) => {
    for (const [key, value] of form) {
      const current = state.settings[key];
      state.settings[key] = typeof current === 'boolean' ? value === 'true' : typeof current === 'number' ? Number(value) : value;
    }
    return { status: 200, body: { ok: true, status: 'saved', applied: [...form.keys()], pending: [], rejected: [], message: '' } };
  };
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(typeof body === 'string' ? body : JSON.stringify(body)); };
    if (path === '/') { documents++; return send(page(), 'text/html'); }
    if (SCRIPTS.includes(path.slice(1))) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(SCHEMA);
    if (path === '/api/v1/config' && request.method === 'POST') {
      let raw = '';
      for await (const chunk of request) raw += chunk;
      const form = new URLSearchParams(raw);
      posts.push(Object.fromEntries(form));
      const reply = await (onPost || apply)(form, { apply, state });
      response.statusCode = reply.status;
      return send({ ...reply.body, ...state });
    }
    if (path === '/api/v1/config' || path === '/api/v1/config/values') return send(state);
    if (path === '/api/v1/apps') return send({ apps: [] });
    if (path === '/api/v1/config/home-dashboards') return send({ queried: true, items: [], default: { explicit: false, path: '' } });
    if (path === '/api/v1/radio') return send({ present: false });
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, state, posts, documents: () => documents, url: `http://127.0.0.1:${server.address().port}`, apply };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

async function open(engine, t, options) {
  const h = await harness(options);
  const browser = await engine.type.launch(engine.launch);
  t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
  const p = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await p.goto(h.url);
  await p.locator('#cfg-camera_kbps input').waitFor();
  return { ...h, p };
}

const toggle = (p, key) => p.locator(`#cfg-${key} [role=switch]`);
const checked = (p, key) => toggle(p, key).getAttribute('aria-checked');
const until = async (what, predicate, ms = 1000) => {
  const end = Date.now() + ms;
  for (;;) {
    if (await predicate()) return;
    if (Date.now() > end) assert.fail(`${what} within ${ms} ms`);
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
};

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;

  engineTest(`${engine.name}: a toggle sends its one setting at once`, async (t) => {
    const h = await open(engine, t);
    await toggle(h.p, 'dashboard_fullscreen').click();
    await until('one settings write', () => h.posts.length === 1);
    assert.deepEqual(h.posts, [{ dashboard_fullscreen: 'true' }]);
    assert.equal(h.state.settings.dashboard_fullscreen, true);
    assert.equal(await checked(h.p, 'dashboard_fullscreen'), 'true');
    await h.p.waitForTimeout(300);
    assert.equal(h.documents(), 1, 'a send does not reload the page');
  });

  engineTest(`${engine.name}: a change made elsewhere shows within a second without reloading`, async (t) => {
    const h = await open(engine, t);
    await new Promise((resolve) => setTimeout(resolve, 700));   // the family's first update has arrived
    h.state.settings.dashboard_fullscreen = true;
    h.state.settings.camera_kbps = 3000;
    await until('the pushed values on the rows', async () => await checked(h.p, 'dashboard_fullscreen') === 'true' &&
      await h.p.locator('#cfg-camera_kbps input').inputValue() === '3000');
    assert.equal(h.documents(), 1, 'the page follows the change without a document reload');
    assert.deepEqual(h.posts, [], 'a pushed value is not sent back');
  });

  engineTest(`${engine.name}: repeated stepper presses send once, a second after the last; typing sends on leaving`, async (t) => {
    const h = await open(engine, t);
    const fps = h.p.locator('#cfg-camera_fps input');
    await fps.focus();
    for (let i = 0; i < 3; i++) { await h.p.keyboard.press('ArrowUp'); await h.p.waitForTimeout(150); }
    await h.p.waitForTimeout(500);
    assert.deepEqual(h.posts, [], 'nothing is sent while presses may still follow');
    await until('one write after the debounce', () => h.posts.length === 1, 1500);
    assert.deepEqual(h.posts, [{ camera_fps: '18' }]);
    await fps.fill('24');
    await h.p.waitForTimeout(1300);
    assert.equal(h.posts.length, 1, 'typed digits wait for the field to be committed');
    await h.p.keyboard.press('Tab');
    await until('the typed value after leaving the field', () => h.posts.length === 2);
    assert.deepEqual(h.posts[1], { camera_fps: '24' });
  });

  // On a real panel the microphone family, the brightness load and the voice catalogue each re-render the
  // form whenever they answer; the same render entry is called here between presses and keystrokes.
  engineTest(`${engine.name}: a re-render from another card neither pulls focus out of a field nor sends early`, async (t) => {
    const h = await open(engine, t);
    const rerender = () => h.p.evaluate(() => window.ConfigurePage.render());
    const fps = h.p.locator('#cfg-camera_fps input');
    await fps.focus();
    await h.p.keyboard.press('ArrowUp');
    await rerender();
    await h.p.keyboard.press('ArrowUp');
    await rerender();
    await h.p.keyboard.press('ArrowUp');
    await h.p.waitForTimeout(500);
    assert.deepEqual(h.posts, [], 'no send while presses may still follow');
    await until('one write after the debounce', () => h.posts.length === 1, 1500);
    assert.deepEqual(h.posts, [{ camera_fps: '18' }]);
    const id = h.p.locator('#cfg-panel_id input');
    await id.click();
    await h.p.keyboard.press('End');
    await h.p.keyboard.type('_x');
    await rerender();
    await h.p.keyboard.type('y');
    assert.equal(await h.p.evaluate(() => document.activeElement && document.activeElement.closest('#cfg-panel_id') !== null), true, 'focus stays in the field');
    await h.p.keyboard.press('Tab');
    await until('the typed value after leaving the field', () => h.posts.length === 2);
    assert.deepEqual(h.posts[1], { panel_id: 'office_panel_xy' });
  });

  engineTest(`${engine.name}: a refused change puts the panel's value back and says why`, async (t) => {
    const h = await open(engine, t, { onPost: async () => ({ status: 400, body: { ok: false, message: 'Fullscreen is not available on this panel' } }) });
    await toggle(h.p, 'dashboard_fullscreen').click();
    await until('the refusal on the row', async () => (await h.p.locator('#cfg-dashboard_fullscreen .cfg-row-message').count()) === 1);
    assert.equal(await checked(h.p, 'dashboard_fullscreen'), 'false');
    assert.equal(await h.p.locator('#cfg-dashboard_fullscreen .cfg-row-message').textContent(), 'Not saved: Fullscreen is not available on this panel');
    assert.equal(await h.p.locator('#cfg-msg').textContent(), 'Not saved: Fullscreen is not available on this panel', 'announced once');
  });

  engineTest(`${engine.name}: a pushed value does not overwrite a change in flight`, async (t) => {
    let release;
    const h = await open(engine, t, {
      onPost: async (form, { apply, state }) => {
        state.settings.camera_kbps = 3000;   // someone else changes another setting while ours is in flight
        await new Promise((resolve) => { release = resolve; });
        return apply(form);
      },
    });
    await toggle(h.p, 'dashboard_fullscreen').click();
    await until('the other change pushed during the send', async () => await h.p.locator('#cfg-camera_kbps input').inputValue() === '3000');
    assert.equal(await checked(h.p, 'dashboard_fullscreen'), 'true', 'the stale pushed value does not undo the change in flight');
    release();
    await until('the settled row', async () => h.state.settings.dashboard_fullscreen === true);
    await h.p.waitForTimeout(300);
    assert.equal(await checked(h.p, 'dashboard_fullscreen'), 'true');
  });

  engineTest(`${engine.name}: a setting the dashboard does not show yet says so until it does`, async (t) => {
    const h = await open(engine, t);
    const note = h.p.locator('#cfg-dashboard_theme .renderer-pending-status');
    h.state.renderer_pending = ['dashboard_theme'];
    await until('the not-on-screen note', async () => (await note.count()) === 1);
    assert.equal(await note.textContent(), 'Saved; not on the dashboard yet.');
    h.state.renderer_pending = [];
    await until('the note to go once the dashboard shows it', async () => (await note.count()) === 0);
  });

  engineTest(`${engine.name}: typing keeps focus and text while a push arrives; the push lands on leaving`, async (t) => {
    const h = await open(engine, t);
    const id = h.p.locator('#cfg-panel_id input');
    await id.click();
    await h.p.keyboard.type('_x');
    h.state.settings.camera_kbps = 4000;
    await h.p.waitForTimeout(1200);
    assert.equal(await id.evaluate((el) => el === document.activeElement), true, 'the field keeps focus');
    assert.equal(await id.inputValue(), 'office_panel_x');
    await h.p.keyboard.press('Tab');
    await until('the typed value sent and the pushed one shown', async () => h.posts.length === 1 &&
      await h.p.locator('#cfg-camera_kbps input').inputValue() === '4000');
    assert.deepEqual(h.posts, [{ panel_id: 'office_panel_x' }]);
  });
}
