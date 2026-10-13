// The page transport's socket implementation settles every request: an interim pending_approval (marked
// `interim`) waits for the panel's final answer, and a pending_approval without it is the final answer, as an
// HTTP 202 approval challenge is.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');

function pageWithSocket() {
  const sockets = [];
  class FakeSocket {
    constructor(url) { this.url = url; this.sent = []; sockets.push(this); setImmediate(() => this.onopen && this.onopen()); }
    send(text) { this.sent.push(JSON.parse(text)); }
    close() { if (this.onclose) this.onclose(); }
    answer(frame) { this.onmessage({ data: JSON.stringify(frame) }); }
  }
  const window = {};
  const document = { body: { dataset: { uiSocket: 'ui' } }, baseURI: 'http://panel.test/', addEventListener() {} };
  vm.runInNewContext(source, { window, document, WebSocket: FakeSocket, URL, setTimeout, clearTimeout, setImmediate, Promise, location: { href: 'http://panel.test/' } });
  return { transport: window.PaneldTransport, sockets };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('a pending_approval without interim is final', async () => {
  const { transport, sockets } = pageWithSocket();
  const reply = transport.request('backup', {});
  await settle(); await settle();
  const sent = sockets[0].sent[0];
  sockets[0].answer({ command_id: sent.command_id, outcome: 'pending_approval', result: { error: 'approval-required' } });
  const answered = await reply;
  assert.equal(answered.outcome, 'pending_approval');
  transport.close();
});

test('an interim pending_approval waits for the final answer', async () => {
  const { transport, sockets } = pageWithSocket();
  let settled = null;
  const reply = transport.command('reboot', 'PRESS').then((answer) => { settled = answer; });
  await settle(); await settle();
  const sent = sockets[0].sent[0];
  sockets[0].answer({ command_id: sent.command_id, outcome: 'pending_approval', interim: true, result: { approval_id: 'a1' } });
  await settle();
  assert.equal(settled, null, 'an interim answer does not settle the command');
  sockets[0].answer({ command_id: sent.command_id, outcome: 'applied' });
  await reply;
  assert.equal(settled.outcome, 'applied');
  transport.close();
});
