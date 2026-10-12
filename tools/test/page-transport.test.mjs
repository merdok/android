// The page transport (native transport protocol section 20, Epic E slice 2) through a real browser, in
// Chromium and WebKit, on both implementations: the HTTP routes, and the WebSocket stub door served by
// the fixture dispatcher (fixtures/ui-socket.mjs) that every layout fixture uses. Plus the static rules
// that keep the pages on the interface.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { createServer } from 'node:http';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { attachUiSocket, transportTables } from './fixtures/ui-socket.mjs';

const ASSETS = new URL('../../app/src/main/assets/', import.meta.url);
const transportSource = readFileSync(new URL('transport.js', ASSETS), 'utf8');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const engines = [
  { name: 'Chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'WebKit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

// A panel fixture: a few routes over HTTP, the same data behind the fake stub door.
async function panel() {
  const state = { perf: 1, reads: [], posts: [] };
  const screenshot = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4, 5]);
  function route(method, url, body) {
    const path = new URL(url, 'http://panel.test/').pathname;
    if (method === 'GET') state.reads.push(path);
    if (path === '/health') return { type: 'text/plain', body: `ha-paneld build=b1 cfg=c${state.perf}` };
    if (path === '/api/v1/peers') return { body: JSON.stringify([{ panel_id: 'a', name: 'Alpha' }]) };
    if (path === '/api/v1/perf') return { body: JSON.stringify({ cpu: state.perf }) };
    if (path === '/api/v1/screenshot.png') return { type: 'image/png', body: screenshot, headers: { 'x-ha-paneld-screenshot-id': 's7' } };
    if (path === '/api/v1/auto-sleep/source' && method === 'POST') { state.posts.push({ path, body: String(body) }); return { body: JSON.stringify({ ok: true, echo: JSON.parse(body) }) }; }
    if (path === '/api/v1/display/density' && method === 'POST') { state.posts.push({ path, body: String(body) }); return { body: JSON.stringify({ ok: true }) }; }
    if (path === '/api/v1/restore' && method === 'POST') {
      state.posts.push({ path, body: Buffer.from(body).toString('hex'), query: new URL(url, 'http://panel.test/').search });
      return { body: JSON.stringify({ restored: Buffer.from(body).length }) };
    }
    if (path === '/api/v1/uninstall') return { status: 409, body: JSON.stringify({ error: 'busy' }) };
    return { status: 404, body: JSON.stringify({ error: 'not found' }) };
  }
  const page = (socket) => `<!doctype html><html><head><base href="/"><script>${transportSource}</script></head>
<body${socket ? ' data-ui-socket="ui"' : ''}><form id="density" method="post" action="api/v1/display/density?lang=de"><input name="density" value="320"><button>Apply</button><span role="status"></span></form></body></html>`;
  const server = createServer((request, response) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      const path = new URL(request.url, 'http://panel.test/').pathname;
      if (path === '/http' || path === '/socket') { response.writeHead(200, { 'content-type': 'text/html' }); response.end(page(path === '/socket')); return; }
      const answer = route(request.method, request.url, Buffer.concat(chunks));
      response.writeHead(answer.status || 200, { 'content-type': answer.type || 'application/json', ...(answer.headers || {}) });
      response.end(answer.body);
    });
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const door = await attachUiSocket(server, async ({ method, url, body }) => {
    const answer = route(method, url, body);
    return { status: answer.status, type: answer.type || 'application/json', body: answer.body, headers: answer.headers };
  }, { record: null, floors: { perf: 300 } });
  return { state, door, origin: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((done) => { server.close(done); server.closeAllConnections(); }) };
}

async function open(t, engine, rig, kind) {
  const browser = await engine.type.launch({ headless: true, ...engine.launch });
  t.after(() => browser.close());
  const page = await browser.newPage();
  await page.goto(`${rig.origin}/${kind}`);
  return page;
}

for (const engine of engines) {
  const browserTest = engine.available ? test : test.skip;

  browserTest(`${engine.name}: a request answers the same outcome and result on both implementations`, async (t) => {
    const rig = await panel(); t.after(rig.close);
    const answers = {};
    for (const kind of ['http', 'socket']) {
      const page = await open(t, engine, rig, kind);
      answers[kind] = await page.evaluate(async () => {
        const T = window.PaneldTransport;
        const peers = await T.request('peers');
        const health = await T.request('health');
        const post = await T.request('auto_sleep.source', { area_key: 'hall', included: true });
        const busy = await T.request('uninstall', { pkg: 'x' });
        const unknown = await T.request('no.such.name', {});
        const shot = await T.request('screenshot');
        const bytes = Array.from(new Uint8Array(await shot.result.arrayBuffer()));
        const restore = await T.request('restore', { dry_run: 1, passphrase: 'pw', blob: new Blob([new Uint8Array([7, 8, 9])]) });
        const strip = (reply) => ({ outcome: reply.outcome, code: reply.code, result: reply.result });
        return { peers: strip(peers), health: strip(health), post: strip(post), busy: strip(busy), unknown: { outcome: unknown.outcome, code: unknown.code },
          shot: { outcome: shot.outcome, bytes, id: shot.headers && shot.headers['x-ha-paneld-screenshot-id'] }, restore: strip(restore) };
      });
    }
    assert.deepEqual(answers.socket, answers.http);
    assert.deepEqual(answers.http.peers, { outcome: 'applied', code: undefined, result: [{ panel_id: 'a', name: 'Alpha' }] });
    assert.equal(answers.http.health.result, 'ha-paneld build=b1 cfg=c1');
    assert.deepEqual(answers.http.post.result, { ok: true, echo: { area_key: 'hall', included: true } });
    assert.deepEqual(answers.http.busy, { outcome: 'refused', code: 'not_commandable', result: { error: 'busy' } });
    assert.deepEqual(answers.http.unknown, { outcome: 'refused', code: 'unknown_command' });
    assert.deepEqual(answers.http.shot, { outcome: 'applied', bytes: [0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4, 5], id: 's7' });
    assert.deepEqual(answers.http.restore.result, { restored: 3 });
    const restores = rig.state.posts.filter((post) => post.path === '/api/v1/restore');
    assert.deepEqual(restores.map((post) => post.body), ['070809', '070809'], 'both implementations deliver the same bytes');
  });

  browserTest(`${engine.name}: an unknown name is refused as unknown_command without reaching a route`, async (t) => {
    const rig = await panel(); t.after(rig.close);
    for (const kind of ['http', 'socket']) {
      const page = await open(t, engine, rig, kind);
      const apiReads = () => rig.state.reads.filter((path) => path.startsWith('/api/')).length;
      const reads = apiReads();
      const reply = await page.evaluate(() => window.PaneldTransport.request('perf.everything', {}));
      assert.equal(reply.outcome, 'refused', kind);
      assert.equal(reply.code, 'unknown_command', kind);
      assert.equal(apiReads(), reads, `${kind}: no route was read`);
    }
  });

  browserTest(`${engine.name}: a subscription delivers the current value, then changes, and stops when closed`, async (t) => {
    const rig = await panel(); t.after(rig.close);
    for (const kind of ['http', 'socket']) {
      rig.state.perf = 1;
      const page = await open(t, engine, rig, kind);
      await page.evaluate(() => {
        window.seen = [];
        window.handle = window.PaneldTransport.subscribe('perf', (value, state) => window.seen.push(state === 'known' ? value.cpu : state));
      });
      await page.waitForFunction(() => window.seen.length === 1);
      rig.state.perf = 2;
      await page.waitForFunction(() => window.seen.length === 2, null, { timeout: 6000 });
      await page.evaluate(() => window.handle.close());
      rig.state.perf = 3;
      await page.waitForTimeout(2600);
      assert.deepEqual(await page.evaluate(() => window.seen), [1, 2], kind);
    }
  });

  browserTest(`${engine.name}: a hidden tab ends its subscriptions and opens them again on return`, async (t) => {
    const rig = await panel(); t.after(rig.close);
    for (const kind of ['http', 'socket']) {
      rig.state.perf = 1;
      const page = await open(t, engine, rig, kind);
      const setHidden = (hidden) => page.evaluate((value) => {
        Object.defineProperty(document, 'hidden', { value, configurable: true });
        document.dispatchEvent(new Event('visibilitychange'));
      }, hidden);
      await page.evaluate(() => { window.seen = []; window.PaneldTransport.subscribe('perf', (value) => window.seen.push(value && value.cpu)); });
      await page.waitForFunction(() => window.seen.length === 1);
      await setHidden(true);
      const subscribes = () => rig.door.log.filter((entry) => entry.in && entry.in.op === 'subscribe').length;
      const unsubscribes = () => rig.door.log.filter((entry) => entry.in && entry.in.op === 'unsubscribe').length;
      await page.waitForTimeout(200);
      const readsWhileHidden = rig.state.reads.filter((path) => path === '/api/v1/perf').length;
      const before = kind === 'socket' ? { subscribes: subscribes(), unsubscribes: unsubscribes() } : null;
      rig.state.perf = 2;
      await page.waitForTimeout(2600);
      assert.deepEqual(await page.evaluate(() => window.seen), [1], `${kind}: nothing arrives while hidden`);
      if (kind === 'http') assert.equal(rig.state.reads.filter((path) => path === '/api/v1/perf').length, readsWhileHidden, 'http: no poll while hidden');
      else assert.equal(before.unsubscribes, 1, 'socket: the hidden tab unsubscribed');
      await setHidden(false);
      await page.waitForFunction(() => window.seen.length === 2);
      assert.deepEqual(await page.evaluate(() => window.seen), [1, 2], `${kind}: the return re-subscribes and delivers the current value`);
      if (kind === 'socket') assert.equal(subscribes(), before.subscribes + 1, 'socket: subscribed again on return');
    }
  });

  browserTest(`${engine.name}: a server-rendered api/v1 form posts through the transport`, async (t) => {
    const rig = await panel(); t.after(rig.close);
    for (const kind of ['http', 'socket']) {
      const page = await open(t, engine, rig, kind);
      const posts = rig.state.posts.length;
      await Promise.all([page.waitForNavigation(), page.locator('#density button').click()]);
      assert.equal(new URL(page.url()).pathname, `/${kind}`, `${kind}: the page reloaded instead of navigating to the API answer`);
      assert.deepEqual(rig.state.posts.slice(posts), [{ path: '/api/v1/display/density', body: 'density=320' }], kind);
      if (kind === 'socket') assert.ok(rig.door.log.some((entry) => entry.in && entry.in.op === 'display.density' && entry.in.args.density === '320'));
    }
  });
}

test('the in-page SHA-256 used for blob references matches node:crypto', () => {
  const { sha256Hex } = transportTables();
  for (const size of [0, 1, 55, 56, 63, 64, 65, 1000, 70000]) {
    const bytes = new Uint8Array(size).map((_, i) => (i * 31 + size) & 255);
    assert.equal(sha256Hex(bytes), createHash('sha256').update(bytes).digest('hex'), `size ${size}`);
  }
});

// Every route a page module or a server-rendered page calls (route inventory 2026-10-12, 20.6 callers
// column), as METHOD and path under /api/v1/ ("/" for root paths).
const PAGE_CALLED = [
  'GET /health', 'GET apps', 'GET packages', 'GET auto-brightness', 'GET auto-brightness/history', 'GET auto-brightness/sources',
  'POST auto-brightness/reset', 'POST auto-brightness/resume', 'GET auto-sleep', 'GET auto-sleep/history', 'GET auto-sleep/prerequisite',
  'POST auto-sleep/source', 'POST backup', 'POST restore', 'GET camera/status', 'POST companion/repair-url', 'GET config', 'POST config',
  'GET config/schema', 'GET config/discovery', 'GET config/ha-area', 'GET config/home-dashboards', 'GET config/export', 'POST config/import',
  'POST dashboard/clear-storage', 'GET dashboard/entities', 'GET dashboard/entities/sync', 'GET dashboard/entities/issues',
  'POST dashboard/entities/activate', 'POST dashboard/entities/issues', 'POST dashboard/entities/override', 'POST dashboard/entities/overrides',
  'POST dashboard/entities/policy', 'POST dashboard/entities/reset', 'POST dashboard/entities/sync', 'GET dashboard/entities/export',
  'GET display', 'POST display/density', 'POST ha/oauth/start', 'GET ha/oauth/status', 'POST hand-back-home', 'GET info', 'POST input',
  'GET inspect', 'POST inspect/start', 'POST inspect/stop', 'POST install/apk', 'POST install/apk/allow', 'POST install/apk/commit',
  'POST install/apk/discard', 'POST install/apk/fetch/cancel', 'POST install/apk/from-url', 'GET install/apk/pending', 'GET install/status',
  'POST install/component', 'GET install/versions', 'GET logs/stream', 'POST migration-notice/dismiss', 'GET peers', 'GET perf',
  'GET power-safety', 'POST power-safety/acknowledge', 'POST power-safety/repair', 'GET profiles', 'GET profiles/{id}/revisions/{revision}',
  'GET profiles/device-draft', 'GET profiles/drivers', 'GET profiles/report', 'GET profiles/schema', 'GET profiles/template',
  'POST profiles/compare', 'POST profiles/preview', 'POST profiles/probe', 'POST profiles/validate', 'POST profiles/activate',
  'POST profiles/delete', 'POST profiles/import', 'POST profiles/rollback', 'POST profiles/save', 'POST profiles/select',
  'GET proximity', 'POST proximity/calibration', 'GET radio', 'POST radio/join', 'GET screenshot.png', 'GET sensors', 'GET setup',
  'POST setup/attest', 'POST setup/entity-filter', 'POST setup/home-dashboard', 'POST setup/identity', 'GET status', 'POST tame',
  'POST tame/record', 'GET tame/suggest', 'POST uninstall', 'POST updates/ignore', 'GET voice/microphone', 'GET voice/pipelines',
  'GET voice/wake-words', 'POST voice/wake-words', 'POST voice/test', 'POST action', 'GET openapi.json',
];

test('the name table names every route a page calls, and every name follows the 20.3 shape', () => {
  const { routes } = transportTables();
  const named = new Set(Object.values(routes).map((entry) => (typeof entry === 'string' ? entry : entry[0])));
  const missing = PAGE_CALLED.filter((route) => !named.has(route));
  assert.deepEqual(missing, [], 'page-called routes without a name');
  for (const name of Object.keys(routes)) assert.match(name, /^[a-z][a-z0-9_.]{0,63}$/, name);
});

test('no page module reaches an api/v1 route, opens an event stream or owns a polling interval outside the transport', () => {
  const offenders = [];
  for (const file of readdirSync(ASSETS).filter((name) => /\.(js|html)$/.test(name) && name !== 'transport.js')) {
    const source = readFileSync(new URL(file, ASSETS), 'utf8');
    source.split('\n').forEach((line, index) => {
      if (/api\/v1\//.test(line)) offenders.push(`${file}:${index + 1} names an api/v1 route`);
      if (/new EventSource\b/.test(line)) offenders.push(`${file}:${index + 1} opens an event stream`);
      if (/\bsetInterval\s*\(/.test(line)) offenders.push(`${file}:${index + 1} owns an interval`);
    });
  }
  assert.deepEqual(offenders, []);
});

test('the recorded stub-door session speaks exactly the 20.2 and 20.4 objects', () => {
  const session = JSON.parse(readFileSync(new URL('./fixtures/ui-socket-replay.json', import.meta.url), 'utf8'));
  const { routes, families } = transportTables();
  assert.ok(session.length >= 10, 'a session was recorded');
  const asked = new Map();
  for (const entry of session) {
    if (entry.in) {
      const message = entry.in;
      assert.ok(['manage', 'command'].includes(message.kind), JSON.stringify(message));
      assert.equal(typeof message.command_id, 'string');
      assert.equal(typeof message.deadline_ms, 'number');
      if (message.kind === 'manage') {
        assert.ok(message.op in routes || ['subscribe', 'unsubscribe', 'blob.upload'].includes(message.op), `unnamed op ${message.op}`);
        if (message.op === 'subscribe') assert.ok(message.args.family in families, message.args.family);
      }
      asked.set(message.command_id, message);
    } else if (entry.out.subscription) {
      assert.ok(Array.isArray(entry.out.observations) && entry.out.observations.length === 1);
      assert.ok(['known', 'unavailable'].includes(entry.out.observations[0].state));
    } else {
      assert.ok(asked.has(entry.out.command_id), 'every answer names a request');
      assert.ok(['applied', 'superseded', 'pending_approval', 'refused', 'failed'].includes(entry.out.outcome));
      if (['refused', 'failed'].includes(entry.out.outcome)) assert.equal(typeof entry.out.code, 'string');
    }
  }
});
