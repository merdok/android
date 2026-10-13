// The page transport never sends a request after it has failed it: a request queued while the socket is
// still connecting, then rejected by its timeout or by the socket closing, leaves the queue, so a later
// connection cannot apply a write the page already reported as failed. Real transport.js, fake timers.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');

function page() {
  const sockets = [], timers = [];
  class FakeSocket {
    constructor() { this.sent = []; sockets.push(this); }
    send(text) { this.sent.push(JSON.parse(text)); }
    close() { this.onclose(); }
  }
  const window = {};
  vm.runInNewContext(source, {
    window, URL, Promise, Blob, Uint8Array, DataView,
    document: { body: { dataset: { uiSocket: 'ui' } }, baseURI: 'http://panel.test/', hidden: false, addEventListener() {} },
    location: { href: 'http://panel.test/' },
    WebSocket: FakeSocket,
    Math: Object.assign(Object.create(Math), { random: () => 0.5 }),
    setTimeout(fn, ms) { timers.push({ fn, ms }); return timers.length; },
    clearTimeout() {},
    fetch() { throw new Error('unexpected fetch'); },
  });
  return { transport: window.PaneldTransport, sockets, timers };
}

const ticks = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

test('a request that timed out before the socket opened is never sent', async () => {
  const { transport, sockets, timers } = page();
  const outcome = transport.request('display.density', { density: 320 }, { deadline_ms: 1 }).catch((error) => error.message);
  await ticks();
  timers.find((timer) => timer.ms === 5001).fn();
  assert.equal(await outcome, 'no answer from the panel');

  sockets[0].onopen();

  assert.deepEqual(sockets[0].sent, []);
});

test('a request failed by a closed socket is not sent on the reconnection', async () => {
  const { transport, sockets, timers } = page();
  const outcome = transport.request('display.density', { density: 320 }).catch((error) => error.message);
  await ticks();
  sockets[0].onclose();
  assert.equal(await outcome, 'panel socket closed');

  timers.filter((timer) => timer.ms !== 15000).at(-1).fn(); // the reconnection backoff
  assert.equal(sockets.length, 2, 'the transport reconnected');
  sockets[1].onopen();

  assert.deepEqual(sockets[1].sent, []);
});

test('a request still waiting is sent when the socket opens', async () => {
  const { transport, sockets } = page();
  transport.request('display.density', { density: 320 }).catch(() => {});
  await ticks();
  sockets[0].onopen();
  assert.deepEqual(sockets[0].sent.map((frame) => frame.op), ['display.density']);
});
