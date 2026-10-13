// Over HTTP the page transport polls a family with the subscription's args on every read, so the setup
// wizard's presence reaches the route as its header on each poll, not only on a one-off read.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');

test('every HTTP poll of the setup family carries the wizard presence header', async () => {
  const timers = [], requests = [];
  const window = {};
  vm.runInNewContext(source, {
    window, URL, Promise,
    document: { body: { dataset: {} }, baseURI: 'http://panel.test/', hidden: false, addEventListener() {} },
    location: { href: 'http://panel.test/' },
    setTimeout(fn, ms) { timers.push({ fn, ms }); return timers.length; },
    clearTimeout() {},
    fetch(url, init) {
      requests.push({ url, presence: init.headers['X-ha-paneld-setup-presence'] });
      return Promise.resolve({ status: 200, headers: { get: () => 'application/json', forEach() {} }, text: () => Promise.resolve('{}') });
    },
  });
  const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
  const handle = window.PaneldTransport.subscribe('setup', () => {}, { presence: 'active' });
  await settle();
  timers.find((timer) => timer.ms === 2000).fn();
  await settle();

  const setupReads = requests.filter((request) => request.url.startsWith('api/v1/setup'));
  assert.equal(setupReads.length, 2, 'two polls');
  assert.deepEqual(setupReads.map((request) => request.presence), ['active', 'active']);
  handle.close();
});
