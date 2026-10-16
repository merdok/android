// In Panel Assistant's sidebar (<body data-ui-relay>) the page transport speaks the /ui frame protocol by
// postMessage to its parent: requests and answers, subscription updates, the sidebar's closed and open signals,
// recovery after a closed signal, leaving the page, and the deadlines of long operations.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');
const ORIGIN = 'http://ha.test:8123';

// A clock the test advances: timers fire in order, each followed by the microtasks it queued.
function clock() {
  let now = 0, next = 1;
  const timers = new Map();
  return {
    get now() { return now; },
    setTimeout(fn, ms) { const id = next++; timers.set(id, { fn, at: now + (ms || 0) }); return id; },
    clearTimeout(id) { timers.delete(id); },
    async advance(ms) {
      const until = now + ms;
      for (;;) {
        let due = null;
        for (const [id, timer] of timers) if (timer.at <= until && (!due || timer.at < due.timer.at)) due = { id, timer };
        if (!due) break;
        timers.delete(due.id);
        now = due.timer.at;
        due.timer.fn();
        await settle();
      }
      now = until;
    },
  };
}

function load(fetch = () => { throw new Error('the relay never fetches a REST route'); }) {
  const posted = [];
  const listeners = {};
  const time = clock();
  const parent = { postMessage(message, target) { posted.push({ message: JSON.parse(JSON.stringify(message)), target, at: time.now }); } };
  const window = { parent, addEventListener(type, fn) { (listeners[type] ||= []).push(fn); } };
  vm.runInNewContext(source, {
    window, URL, Promise,
    document: { body: { dataset: { uiRelay: '', embedded: '' } }, baseURI: ORIGIN + '/api/panel_assistant/embed/t/', hidden: false, addEventListener() {} },
    location: { href: ORIGIN + '/api/panel_assistant/embed/t/configure', origin: ORIGIN },
    setTimeout: time.setTimeout, clearTimeout: time.clearTimeout,
    fetch, Blob,
    WebSocket: class { constructor() { throw new Error('the relay never opens the stub socket'); } },
  });
  const deliver = (data, from = parent, origin = ORIGIN) => (listeners.message || []).forEach((fn) => fn({ source: from, origin, data }));
  const fire = (type, event = {}) => (listeners[type] || []).forEach((fn) => fn(event));
  return { transport: window.PaneldTransport, posted, deliver, parent, time, fire };
}

const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const frames = (posted) => posted.map((entry) => entry.message.frame);

test('a request leaves as a frame to the parent and its answer resolves it', async () => {
  const { transport, posted, deliver } = load();
  const reply = transport.request('config', { section: 'display' });
  await settle();
  assert.equal(posted.length, 1);
  assert.equal(posted[0].target, ORIGIN, 'posted to the page\'s own origin');
  assert.equal(posted[0].message.type, 'panel-assistant-ui');
  const sent = posted[0].message.frame;
  assert.deepEqual([sent.kind, sent.op, sent.args.section], ['manage', 'config', 'display']);

  // Only the parent's own messages of this type are read.
  deliver({ type: 'panel-assistant-ui', frame: { command_id: sent.command_id, outcome: 'failed', code: 'failed' } }, {});
  deliver({ type: 'other', frame: { command_id: sent.command_id, outcome: 'failed', code: 'failed' } });
  deliver({ type: 'panel-assistant-ui', frame: { command_id: sent.command_id, outcome: 'failed', code: 'failed' } }, undefined, 'http://elsewhere.test');
  deliver({ type: 'panel-assistant-ui', frame: { command_id: sent.command_id, outcome: 'pending_approval', interim: true } });
  deliver({ type: 'panel-assistant-ui', frame: { command_id: sent.command_id, outcome: 'applied', result: { brightness: 40 }, headers: { 'x-a': '1' } } });
  const answer = await reply;
  assert.deepEqual([answer.outcome, answer.result.brightness, answer.headers['x-a']], ['applied', 40, '1']);
});

test("a subscription's updates reach onValue", async () => {
  const { transport, posted, deliver } = load();
  const seen = [];
  transport.subscribe('perf', (value, state, first) => seen.push([state, value && value.cpu, first]));
  await settle();
  const subscribe = frames(posted).find((frame) => frame.op === 'subscribe');
  assert.equal(subscribe.args.family, 'perf');
  deliver({ type: 'panel-assistant-ui', frame: { command_id: subscribe.command_id, outcome: 'applied', result: { subscription: subscribe.command_id } } });
  await settle();
  deliver({ type: 'panel-assistant-ui', frame: { subscription: subscribe.command_id, observations: [{ channel: 'perf', state: 'known', value: { cpu: 7 } }] } });
  deliver({ type: 'panel-assistant-ui', frame: { subscription: subscribe.command_id, observations: [{ channel: 'perf', state: 'known', value: { cpu: 9 } }] } });
  assert.deepEqual(seen, [['known', 7, true], ['known', 9, false]]);
});

// What the sidebar sends: an answer frame, `failed` while its embed session is not open, and the closed and open signals.
const answer = (deliver, frame, outcome = 'applied') => deliver({ type: 'panel-assistant-ui', frame: outcome === 'applied'
  ? { command_id: frame.command_id, outcome, result: { subscription: frame.command_id } }
  : { command_id: frame.command_id, outcome, code: 'failed' } });
const subscribes = (posted, from = 0) => frames(posted.slice(from)).filter((frame) => frame.op === 'subscribe');

test('after the closed signal alone the page resubscribes with backoff and retries a subscribe the recovering relay fails', async () => {
  const { transport, posted, deliver, time } = load();
  const seen = [];
  transport.subscribe('sensors', (value, state) => seen.push(state + ' ' + JSON.stringify(value)));
  await settle();
  const first = subscribes(posted)[0];
  answer(deliver, first);
  await settle();
  const pending = transport.request('status', {});
  await settle();

  deliver({ type: 'panel-assistant-ui', closed: true });
  await assert.rejects(pending, 'a request in flight fails with the relay');
  assert.deepEqual(seen, ['unavailable null']);
  // An update for the old subscription after the close is not delivered.
  deliver({ type: 'panel-assistant-ui', frame: { subscription: first.command_id, observations: [{ channel: 'sensors', state: 'known', value: { lux: 1 } }] } });
  assert.deepEqual(seen, ['unavailable null']);

  // No open signal follows. Each subscribe the sidebar fails is tried again, from 1 s doubling to 30 s.
  const gaps = [];
  let from = posted.length, failedAt = time.now;
  for (let attempt = 0; attempt < 7; attempt++) {
    await time.advance(60000 - 1);
    const again = subscribes(posted, from);
    assert.equal(again.length, 1, 'one subscribe per retry, attempt ' + attempt);
    assert.equal(again[0].args.family, 'sensors');
    gaps.push(posted.find((entry) => entry.message.frame?.command_id === again[0].command_id).at - failedAt);
    from = posted.length;
    if (attempt < 6) { answer(deliver, again[0], 'failed'); failedAt = time.now; await settle(); continue; }
    answer(deliver, again[0]);
    await settle();
    deliver({ type: 'panel-assistant-ui', frame: { subscription: again[0].command_id, observations: [{ channel: 'sensors', state: 'known', value: { lux: 2 } }] } });
  }
  assert.deepEqual(gaps, [1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  assert.equal(seen.at(-1), 'known {"lux":2}');
  // Open again: nothing more is retried.
  const settled = posted.length;
  await time.advance(120000);
  assert.deepEqual(subscribes(posted, settled), []);
});

test("the sidebar's open signal reopens subscriptions at once, also after a retry has reopened the link", async () => {
  const { transport, posted, deliver, time } = load();
  const seen = [];
  transport.subscribe('perf', (value, state) => seen.push(state));
  await settle();
  const first = subscribes(posted)[0];
  answer(deliver, first);
  await settle();

  // A resumed embed session: closed, a retry the still-opening session fails, then open.
  deliver({ type: 'panel-assistant-ui', closed: true });
  await time.advance(1000);
  const retried = subscribes(posted).at(-1);
  assert.notEqual(retried.command_id, first.command_id, 'the retry subscribed again');
  answer(deliver, retried, 'failed');
  await settle();
  const before = posted.length;
  deliver({ type: 'panel-assistant-ui', open: true });
  await settle();
  const reopened = subscribes(posted, before);
  assert.equal(reopened.length, 1, 'subscribed again without waiting for the next retry');
  assert.equal(reopened[0].args.family, 'perf');
  answer(deliver, reopened[0]);
  await settle();
  deliver({ type: 'panel-assistant-ui', frame: { subscription: reopened[0].command_id, observations: [{ channel: 'perf', state: 'known', value: {} }] } });
  assert.equal(seen.at(-1), 'known');
  const after = posted.length;
  await time.advance(120000);
  assert.deepEqual(subscribes(posted, after), [], 'the pending retry was cancelled');
});

test('leaving the page unsubscribes every live relayed subscription', async () => {
  const { transport, posted, deliver, fire } = load();
  transport.subscribe('perf', () => {});
  transport.subscribe('sensors', () => {});
  await settle();
  const opened = subscribes(posted);
  opened.forEach((frame) => answer(deliver, frame));
  await settle();
  const before = posted.length;
  fire('pagehide', { persisted: false });
  await settle();
  const ended = frames(posted.slice(before)).filter((frame) => frame.op === 'unsubscribe').map((frame) => frame.args.subscription);
  assert.deepEqual(ended.sort(), opened.map((frame) => frame.command_id).sort());
});

test('a relayed backup, restore, config import or APK install may take two minutes; other requests keep ten seconds', async () => {
  const { transport, posted } = load();
  for (const op of ['backup', 'restore', 'config.import', 'install.apk', 'config']) transport.request(op, {}).catch(() => {});
  transport.request('input', { x: 1, y: 1 }, { deadline_ms: 60000 }).catch(() => {});
  await settle();
  assert.deepEqual(frames(posted).map((frame) => [frame.op, frame.deadline_ms]), [
    ['backup', 120000], ['restore', 120000], ['config.import', 120000], ['install.apk', 120000], ['config', 10000], ['input', 60000],
  ]);
});

test('after a close the next request reopens the relay and its subscriptions', async () => {
  const { transport, posted, deliver } = load();
  transport.subscribe('camera', () => {});
  await settle();
  deliver({ type: 'panel-assistant-ui', closed: true });
  const before = posted.length;
  transport.request('status', {}).catch(() => {});
  await settle();
  const after = frames(posted.slice(before));
  assert.deepEqual(after.map((frame) => frame.op).sort(), ['status', 'subscribe']);
});

test('a result too large for a frame reads from its blob as it would have inline', async () => {
  const fetched = [];
  const bodies = { 'blob/json1': '{"entities":[1,2]}', 'blob/file1': '{"export":true}' };
  const { transport, posted, deliver } = load((url) => {
    fetched.push(url);
    const text = bodies[url];
    return Promise.resolve({ ok: true, text: () => Promise.resolve(text), blob: () => Promise.resolve(new Blob([text])) });
  });
  const read = transport.request('dashboard.entities', {});
  const download = transport.request('config.export', {});
  await settle();
  const [first, second] = frames(posted);
  const blob = (url, type) => ({ blob: { url, size: 1, sha256: '0'.repeat(64), type } });
  deliver({ type: 'panel-assistant-ui', frame: { command_id: first.command_id, outcome: 'applied', result: blob('blob/json1', 'application/json') } });
  deliver({ type: 'panel-assistant-ui', frame: { command_id: second.command_id, outcome: 'applied', result: blob('blob/file1', 'application/json'), headers: { 'content-disposition': 'attachment; filename="c.json"' } } });
  assert.equal(JSON.stringify((await read).result.entities), '[1,2]');
  assert.ok((await download).result instanceof Blob, 'a download keeps its bytes');
  assert.deepEqual(fetched, ['blob/json1', 'blob/file1'], 'blob URLs resolve against the page base');
});
