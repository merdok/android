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
 field('home_dashboard', 'Dashboard', 'Dashboard', { type: 'STRING', picker: 'ha_dashboard' }),
 field('ha_area', 'Area', 'Identity', { type: 'STRING', picker: 'ha_area' }),
  field('dashboard_fullscreen', 'Fullscreen', 'Dashboard'),
  field('dashboard_theme', 'Theme', 'Dashboard', { type: 'ENUM', options: ['auto', 'light', 'dark'] }),
  field('panel_id', 'Panel ID', 'Identity', { type: 'STRING', maxLength: 63 }),
  field('ha_url', 'Home Assistant address', 'Identity', { type: 'STRING', maxLength: 255 }),
  field('ha_token', 'Access token', 'Identity', { type: 'PASSWORD', secret: true }),
  field('camera_fps', 'Frame rate', 'Camera', { type: 'INT', min: 1, max: 30, step: 1 }),
  field('camera_kbps', 'Bitrate (kbps)', 'Camera', { type: 'INT', min: 250, max: 8000, step: 250 }),
];

// A fake panel. `state` is what Home Assistant (or anyone) last left on it; the test changes it directly to
// stand for a change made elsewhere. `onPost` decides each settings write; by default it applies.
async function harness({ onPost, discovery = {}, dashboardReply } = {}) {
  const state = {
    settings: { home_dashboard: '', ha_area: 'Office', dashboard_fullscreen: false, dashboard_theme: 'auto', panel_id: 'office_panel', ha_url: '', ha_token: '', camera_fps: 15, camera_kbps: 2000 },
    ha_area_user_override: false, ha_expose: {}, ha_auth: { configured: false }, apply_pending: {}, apply_stalled: [], renderer_pending: [],
  };
  const posts = [];
  let documents = 0; let dashboardReads = 0;
  const apply = async (form) => {
    if (form.has('ha_token')) state.ha_auth={configured:true,oauth:false};
    if (form.has('ha_area')) state.ha_area_user_override = true;
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
    if (path === '/api/v1/ha/oauth/status') return send({phase:'connected',display_name:'Panel user',language:'en'});
    if (path === '/api/v1/config/ha-area') return send({queried:true,admin:false,device:{found:true,area_name:'Office'},areas:[{name:'Office'},{name:'Kitchen'}]});
    if (path === '/api/v1/config/home-dashboards') {dashboardReads++; if (dashboardReply) return send(await dashboardReply(dashboardReads)); return send({ queried: true, items: [{path:'/one',title:'First account'}], default: { explicit: false, path: '' } });}
    if (path === '/api/v1/radio') return send({ present: false });
    if (path === '/api/v1/config/discovery') return send(discovery);
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, state, posts, documents: () => documents, dashboardReads: () => dashboardReads, url: `http://127.0.0.1:${server.address().port}`, apply };
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
// Waits pin what happens, not how fast: on a loaded CI runner a browser's timers slip. The latency promise
// (a change shows within a second) is measured on a real panel; see the lane checkpoint.
const until = async (what, predicate, ms = 2000) => {
  const end = Date.now() + ms;
  for (;;) {
    if (await predicate()) return;
    if (Date.now() > end) assert.fail(`${what} within ${ms} ms`);
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
};


for (const engine of engines) {
  test(`${engine.name}: saved Area override stays visible`, async t => {
    const h = await open(engine, t);
    const picker = h.p.locator('#cfg-ha_area select');
    await picker.selectOption('Kitchen');
    await until('Kitchen persisted', () => h.state.settings.ha_area === 'Kitchen');
    await until('override displayed', async () => await picker.inputValue() === 'Kitchen' && await h.p.locator('#cfg-ha_area').textContent().then(text => text.includes('Home Assistant has')));
    await h.p.waitForTimeout(600);
    h.state.settings.ha_area = 'Office'; h.state.ha_area_user_override = false;
    await h.p.locator('#cfg-camera_kbps input').focus();
    await until('canonical Area adopted', async () => await picker.inputValue() === 'Office');
    h.state.settings.ha_area = 'Kitchen'; h.state.ha_area_user_override = true;
    await until('other page override adopted', async () => await picker.inputValue() === 'Kitchen');
    assert.equal(h.posts.filter(post => 'ha_area' in post).length, 1);
  });
  test(`${engine.name}: first token populates dashboards without reload`, async t => {
    const h = await open(engine, t);
    await h.p.locator('#cfg-ha_url input').fill('http://ha.local:8123');
    await h.p.keyboard.press('Tab');
    await h.p.locator('#cfg-ha_token input').fill('first-account-token');
    await h.p.keyboard.press('Tab');
    await until('credential persisted', () => h.state.ha_auth.configured === true);
    await until('dashboard choices fetched', () => h.dashboardReads() === 1);
    await until('picker populated', () => h.p.locator('#cfg-home_dashboard').textContent().then(text => text.includes('First account')));
    assert.equal(h.documents(), 1);
  });
  test(`${engine.name}: OAuth completion populates dashboards`, async t => {
    const h = await open(engine, t);
    await h.p.evaluate(() => { const channel = new BroadcastChannel('ha-paneld-ha-oauth'); channel.postMessage({ type: 'ha-oauth-result', status: 'success' }); channel.close(); });
    await until('OAuth catalogue fetched', () => h.dashboardReads() === 1);
    await until('picker populated', () => h.p.locator('#cfg-home_dashboard').textContent().then(text => text.includes('First account')));
  });
}

for (const engine of engines) {
  test(`${engine.name}: a later account rejects delayed old dashboards and keeps a custom path`, async t => {
    let releaseOld;
    const old = new Promise(resolve => { releaseOld = resolve; });
    const catalog = title => ({ queried: true, items: [{ path: '/' + title, title }], default: { explicit: false, path: '' } });
    const h = await open(engine, t, { dashboardReply: async count => count === 1 ? await old : catalog('New account') });
    h.state.settings.home_dashboard = '/custom/view';
    await until('custom path adopted', () => h.p.locator('#cfg-home_dashboard input').inputValue().then(value => value === '/custom/view'));
    await h.p.locator('#cfg-ha_url input').fill('http://ha.local:8123');
    await h.p.keyboard.press('Tab');
    await h.p.locator('#cfg-ha_token input').fill('first-token');
    await h.p.keyboard.press('Tab');
    await until('old request is in flight', () => h.dashboardReads() === 1);
    await h.p.locator('#cfg-ha_token input').fill('second-token');
    await h.p.keyboard.press('Tab');
    await until('new account populated', () => h.p.locator('#cfg-home_dashboard').textContent().then(text => text.includes('New account')));
    releaseOld(catalog('Old account'));
    await h.p.waitForTimeout(100);
    assert.equal(await h.p.locator('#cfg-home_dashboard').textContent().then(text => text.includes('Old account')), false);
    assert.equal(await h.p.locator('#cfg-home_dashboard input').inputValue(), '/custom/view');
    assert.equal(h.posts.some(post => 'home_dashboard' in post), false);
  });
}
