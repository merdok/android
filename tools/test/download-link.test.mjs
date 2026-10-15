// The page shell's download handler (PageShell.kt) over the socket: a modified click keeps the plain link, a link
// to a new tab opens that tab inside the click, and a saved file keeps the panel's own name.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const shell = readFileSync(new URL('../../app/src/main/kotlin/io/panelassistant/android/http/PageShell.kt', import.meta.url), 'utf8');
const script = shell.match(/<script>(\/\* Over the stub's socket a download link[\s\S]*?)<\/script>/)[1];

function load(reply, door = 'data-ui-socket') {
  let onClick;
  const calls = [], saved = [];
  const window = {
    open(url, target) { calls.push(['open', url, target]); return { opener: 'x', location: {}, close() { calls.push(['close']); } }; },
    alert(text) { calls.push(['alert', text]); },
  };
  const document = {
    addEventListener(name, fn) { if (name === 'click') onClick = fn; },
    body: { hasAttribute: (name) => name === door, appendChild() {} },
    createElement: () => ({ click() { saved.push(this.download); }, remove() {} }),
  };
  window.PaneldTransport = { request(op) { calls.push(['request', op]); return Promise.resolve(reply); } };
  vm.runInNewContext(script, {
    window, document, PaneldTransport: window.PaneldTransport, Blob, URL: { createObjectURL: () => 'blob:x', revokeObjectURL() {} },
    setTimeout() {},
  });
  const click = async (link, modifiers = {}) => {
    const event = { target: { closest: () => link }, defaultPrevented: false, button: 0, ...modifiers, preventDefault() { this.defaultPrevented = true; } };
    onClick(event);
    const synchronous = calls.slice();
    for (let i = 0; i < 10; i++) await Promise.resolve();
    return { event, synchronous };
  };
  return { click, calls, saved };
}

const link = (target) => ({ target, getAttribute: (name) => ({ 'data-op': 'config.export', 'data-file': 'config.json' })[name] });

test('a modified click is left to the plain link', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']) });
  for (const modifier of ['ctrlKey', 'metaKey', 'shiftKey', 'altKey']) {
    const { event } = await rig.click(link(''), { [modifier]: true });
    assert.equal(event.defaultPrevented, false, modifier);
  }
  const { event } = await rig.click(link(''), { button: 1 });
  assert.equal(event.defaultPrevented, false, 'middle button');
  assert.deepEqual(rig.calls, []);
});

test('a new tab opens within the click, before the answer', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['png']) });
  const { synchronous } = await rig.click(link('_blank'));
  assert.equal(synchronous[0][0], 'open');
});

test('a saved file keeps the name the panel gave it', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']), headers: { 'content-disposition': 'attachment; filename="panel-7-config.json"' } });
  await rig.click(link(''));
  assert.deepEqual(rig.saved, ['panel-7-config.json']);
});

test("in the sidebar relay a download link goes through the transport too", async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']) }, 'data-ui-relay');
  const { event } = await rig.click(link(''));
  assert.equal(event.defaultPrevented, true);
  assert.deepEqual(rig.calls, [['request', 'config.export']]);
  assert.deepEqual(rig.saved, ['config.json']);
});

test('a page on plain HTTP leaves the link to the browser', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']) }, null);
  const { event } = await rig.click(link(''));
  assert.equal(event.defaultPrevented, false);
  assert.deepEqual(rig.calls, []);
});
