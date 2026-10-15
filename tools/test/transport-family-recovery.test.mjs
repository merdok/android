// Over HTTP the page transport marks a family unavailable when a read fails and sends a fresh known value when it
// recovers, even one equal to the value before the failure, as the socket door does; and the logs family follows
// the selected source.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');

function load(fetch, extra = {}) {
  const timers = [];
  const window = {};
  vm.runInNewContext(source, {
    window, URL, Promise,
    document: { body: { dataset: {} }, baseURI: 'http://panel.test/', hidden: false, addEventListener() {} },
    location: { href: 'http://panel.test/' },
    setTimeout(fn, ms) { timers.push({ fn, ms }); return timers.length; },
    clearTimeout() {},
    fetch,
    ...extra,
  });
  return { transport: window.PaneldTransport, timers };
}

function reply(status, body) {
  return Promise.resolve({ status, headers: { get: () => 'application/json', forEach() {} }, text: () => Promise.resolve(body) });
}

const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

test('a failing family read is unavailable and an identical recovery is known again', async () => {
  const script = [
    () => reply(200, '{"v":1}'),
    () => reply(200, '{"v":1}'),
    () => reply(503, '{"error":"busy"}'),
    () => Promise.reject(new TypeError('network down')),
    () => reply(200, '{"v":1}'),
    () => reply(200, '{"v":1}'),
  ];
  let reads = 0;
  const { transport, timers } = load(() => script[Math.min(reads++, script.length - 1)]());
  const seen = [];
  const handle = transport.subscribe('perf', (value, state) => seen.push(state + ' ' + JSON.stringify(value)));
  await settle();
  for (let poll = 1; poll < script.length; poll++) {
    timers.filter((timer) => timer.ms === 2000).at(-1).fn();
    await settle();
  }
  assert.equal(reads, script.length);
  assert.deepEqual(seen, ['known {"v":1}', 'unavailable null', 'known {"v":1}']);
  handle.close();
});

test('the logs family follows the selected source', async () => {
  const opened = [];
  class EventSource { constructor(url) { opened.push(String(url)); } close() {} }
  const { transport } = load(() => reply(200, '{}'), { EventSource });
  for (const name of ['app', 'system', 'webview']) transport.subscribe('logs', () => {}, { source: name }).close();
  assert.deepEqual(opened.map((url) => new URL(url, 'http://panel.test/').searchParams.get('source')), ['app', 'system', 'webview']);
});
