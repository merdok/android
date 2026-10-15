// In Panel Assistant's sidebar (<body data-ui-relay>) the page transport speaks the /ui frame protocol by
// postMessage to its parent: requests and answers, subscription updates, and the sidebar's closed and open signals.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');
const ORIGIN = 'http://ha.test:8123';

function load() {
  const posted = [];
  const listeners = [];
  const parent = { postMessage(message, target) { posted.push({ message: JSON.parse(JSON.stringify(message)), target }); } };
  const window = { parent, addEventListener(type, fn) { if (type === 'message') listeners.push(fn); } };
  vm.runInNewContext(source, {
    window, URL, Promise,
    document: { body: { dataset: { uiRelay: '', embedded: '' } }, baseURI: ORIGIN + '/api/panel_assistant/embed/t/', hidden: false, addEventListener() {} },
    location: { href: ORIGIN + '/api/panel_assistant/embed/t/configure', origin: ORIGIN },
    setTimeout() { return 0; },
    clearTimeout() {},
    fetch() { throw new Error('the relay never fetches a REST route'); },
    WebSocket: class { constructor() { throw new Error('the relay never opens the stub socket'); } },
  });
  const deliver = (data, from = parent, origin = ORIGIN) => listeners.forEach((fn) => fn({ source: from, origin, data }));
  return { transport: window.PaneldTransport, posted, deliver, parent };
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

test('a closed signal marks subscriptions unavailable and they reopen on the open signal', async () => {
  const { transport, posted, deliver } = load();
  const seen = [];
  transport.subscribe('sensors', (value, state) => seen.push(state + ' ' + JSON.stringify(value)));
  await settle();
  const first = frames(posted).find((frame) => frame.op === 'subscribe');
  deliver({ type: 'panel-assistant-ui', frame: { command_id: first.command_id, outcome: 'applied', result: { subscription: first.command_id } } });
  await settle();
  const pending = transport.request('status', {});
  await settle();

  deliver({ type: 'panel-assistant-ui', closed: true });
  await assert.rejects(pending, 'a request in flight fails with the relay');
  assert.deepEqual(seen, ['unavailable null']);
  // An update for the old subscription after the close is not delivered.
  deliver({ type: 'panel-assistant-ui', frame: { subscription: first.command_id, observations: [{ channel: 'sensors', state: 'known', value: { lux: 1 } }] } });
  assert.deepEqual(seen, ['unavailable null']);

  const before = posted.length;
  deliver({ type: 'panel-assistant-ui', open: true });
  await settle();
  const reopened = frames(posted.slice(before)).find((frame) => frame.op === 'subscribe');
  assert.ok(reopened, 'the open signal subscribes again');
  assert.equal(reopened.args.family, 'sensors');
  deliver({ type: 'panel-assistant-ui', frame: { command_id: reopened.command_id, outcome: 'applied', result: { subscription: reopened.command_id } } });
  await settle();
  deliver({ type: 'panel-assistant-ui', frame: { subscription: reopened.command_id, observations: [{ channel: 'sensors', state: 'known', value: { lux: 2 } }] } });
  assert.deepEqual(seen, ['unavailable null', 'known {"lux":2}']);
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
