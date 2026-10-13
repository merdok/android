// A fake of the panel's stub door (native transport protocol 20.2, 20.4, 20.5) for the browser
// fixtures: one WebSocket at /ui that answers the 20.2 objects from the fixture's existing HTTP data.
// Each request is turned into the HTTP request the page made before slice 2 by the page transport's own
// name -> route table, answered by the fixture's `answer` function, and turned back into a 20.2 reply
// with the transport's own status -> outcome rule, so a page sees the same data on either
// implementation. Subscriptions are read at the family floor and sent on change; binary bodies move as
// one-use blob references served by a small blob server beside the fixture.
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';
import vm from 'node:vm';

const transportSource = readFileSync(new URL('../../../app/src/main/assets/transport.js', import.meta.url), 'utf8');

/** The page transport's own tables and HTTP mapping, loaded from the shipped asset. */
export function transportTables() {
  const window = {};
  vm.runInNewContext(transportSource, { window, document: {} }, { filename: 'transport.js' });
  return window.PaneldTransport;
}

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** Server side of RFC 6455 for text frames: enough for the fixture door. */
function accept(request, socket, onText, onClose) {
  const key = request.headers['sec-websocket-key'];
  const digest = createHash('sha1').update(key + GUID).digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${digest}\r\n\r\n`);
  let buffer = Buffer.alloc(0);
  let fragments = [];
  let open = true;
  function frame(opcode, payload) {
    const length = payload.length;
    const head = length < 126 ? Buffer.from([0x80 | opcode, length])
      : length < 65536 ? Buffer.from([0x80 | opcode, 126, length >> 8, length & 255])
        : Buffer.concat([Buffer.from([0x80 | opcode, 127]), (() => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(length)); return b; })()]);
    return Buffer.concat([head, payload]);
  }
  function close() {
    if (!open) return;
    open = false;
    try { socket.end(frame(0x8, Buffer.alloc(0))); } catch { /* already gone */ }
    onClose();
  }
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    for (;;) {
      if (buffer.length < 2) return;
      const fin = buffer[0] & 0x80; const opcode = buffer[0] & 0x0f;
      let length = buffer[1] & 0x7f; let offset = 2;
      if (length === 126) { if (buffer.length < 4) return; length = buffer.readUInt16BE(2); offset = 4; }
      else if (length === 127) { if (buffer.length < 10) return; length = Number(buffer.readBigUInt64BE(2)); offset = 10; }
      const masked = buffer[1] & 0x80;
      const mask = masked ? buffer.subarray(offset, offset + 4) : null;
      if (masked) offset += 4;
      if (buffer.length < offset + length) return;
      const payload = Buffer.from(buffer.subarray(offset, offset + length));
      buffer = buffer.subarray(offset + length);
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      if (opcode === 0x8) { close(); return; }
      if (opcode === 0x9) { socket.write(frame(0xA, payload)); continue; }
      if (opcode === 0x1 || opcode === 0x0) {
        fragments.push(payload);
        if (fin) { const text = Buffer.concat(fragments).toString('utf8'); fragments = []; onText(text); }
      }
    }
  });
  socket.on('close', () => { if (open) { open = false; onClose(); } });
  socket.on('error', () => {});
  return {
    send(text) { if (open) socket.write(frame(0x1, Buffer.from(text, 'utf8'))); },
    close,
  };
}

/**
 * Attach the fake door to `server` (an http.Server). `answer({method, url, headers, body, socketUrl})`
 * returns the fixture's HTTP answer `{status = 200, type, body}` (body: string or Buffer) for the
 * request the page would have made over HTTP; `url` is relative to the page origin and `socketUrl` is
 * the door URL the page opened (its query names the page in the fixtures). Returns {close(), log}.
 */
export async function attachUiSocket(server, answer, { record = process.env.UI_SOCKET_RECORD, floors = {} } = {}) {
  const tables = transportTables();
  const blobs = new Map();
  let tokens = 0;
  const log = [];
  const blobServer = createServer((request, response) => {
    const cors = { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, PUT', 'access-control-allow-headers': 'content-type' };
    const token = request.url.replace(/^\/blob\//, '');
    if (request.method === 'OPTIONS') { response.writeHead(204, cors); response.end(); return; }
    if (request.method === 'PUT' && blobs.has(token) && blobs.get(token).upload) {
      const parts = [];
      request.on('data', (part) => parts.push(part));
      request.on('end', () => { Object.assign(blobs.get(token), { bytes: Buffer.concat(parts), upload: false }); response.writeHead(200, cors); response.end(); });
      return;
    }
    const entry = blobs.get(token);
    if (request.method !== 'GET' || !entry || entry.upload) { response.writeHead(410, cors); response.end(); return; }
    blobs.delete(token); // one use
    response.writeHead(200, { ...cors, 'content-type': entry.type }); response.end(entry.bytes);
  });
  await new Promise((done) => blobServer.listen(0, '127.0.0.1', done));
  blobServer.unref();
  const blobBase = `http://127.0.0.1:${blobServer.address().port}/blob/`;
  const mint = (entry) => { const token = `t${++tokens}${Math.random().toString(36).slice(2, 8)}`; blobs.set(token, entry); return blobBase + token; };

  // One HTTP-shaped answer -> {outcome, code, result, headers}.
  async function reply(op, args, socketUrl) {
    const http = tables.httpRequestFor(op, args);
    if (!http) return { outcome: 'refused', code: 'unknown_command' };
    let body = http.init.body;
    const ref = args && args.blob;
    if (ref && typeof ref === 'object' && typeof ref.url === 'string') {
      const entry = blobs.get(ref.url.slice(blobBase.length));
      const bytes = entry && entry.bytes;
      if (!bytes || bytes.length !== ref.size || createHash('sha256').update(bytes).digest('hex') !== ref.sha256) {
        return { outcome: 'refused', code: 'invalid_value', result: { error: 'blob-mismatch' } };
      }
      blobs.delete(ref.url.slice(blobBase.length));
      body = bytes;
    }
    let answered;
    try { answered = await answer({ method: http.init.method, url: http.url, headers: http.init.headers, body, socketUrl }); }
    catch (error) { return { outcome: 'failed', code: 'failed', result: { message: String(error && error.message || error) } }; }
    const status = answered.status || 200;
    const type = String(answered.type || 'application/json');
    let result = answered.body;
    if (Buffer.isBuffer(result) || (!/json|^text\//.test(type) && result !== undefined)) {
      const bytes = Buffer.isBuffer(result) ? result : Buffer.from(String(result));
      result = { blob: { url: mint({ bytes, type }), size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), type } };
    } else if (/json/.test(type)) {
      try { result = typeof result === 'string' ? (result ? JSON.parse(result) : {}) : (result ?? {}); } catch { result = { message: result }; }
    }
    const out = { ...tables.outcomeFor(status, result), result };
    if (answered.headers) out.headers = answered.headers;
    return out;
  }

  // An upgraded socket keeps server.close() waiting until the browser hangs up; closing the fixture
  // server ends its doors too.
  const sockets = new Set();
  const closeServer = server.close.bind(server);
  server.close = (callback) => { for (const socket of sockets) socket.destroy(); return closeServer(callback); };
  server.on('upgrade', (request, socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    const socketUrl = request.url;
    if (!socketUrl.startsWith('/ui')) { socket.destroy(); return; }
    const subscriptions = new Map();
    const session = log.length < 400 ? log : null;
    const door = accept(request, socket, async (text) => {
      const message = JSON.parse(text);
      if (session) session.push({ in: message });
      const send = (object) => { if (session && session.length < 400) session.push({ out: object }); door.send(JSON.stringify(object)); };
      const { command_id: id } = message;
      if (message.kind === 'command') {
        // Action channels are accepted without touching the fixture; a setting channel is a settings write.
        const answered = message.channel === 'reload' || message.channel === 'reboot' ? { outcome: 'applied' }
          : await reply('settings', { [message.channel]: message.value }, socketUrl);
        send({ command_id: id, outcome: answered.outcome, ...(answered.code ? { code: answered.code } : {}) });
        return;
      }
      if (message.kind !== 'manage') { send({ command_id: id, outcome: 'refused', code: 'unknown_command' }); return; }
      const args = message.args || {};
      if (message.op === 'blob.upload') {
        send({ command_id: id, outcome: 'applied', result: { url: mint({ upload: true, type: args.type, bytes: null }) } });
        return;
      }
      if (message.op === 'subscribe') {
        const family = tables.families[args.family];
        if (!family) { send({ command_id: id, outcome: 'refused', code: 'unknown_command' }); return; }
        const { family: name, ...rest } = args;
        const sub = { stopped: false, last: undefined, timer: null };
        subscriptions.set(id, sub);
        send({ command_id: id, outcome: 'applied', result: { subscription: id } });
        const update = (value, state = 'known') => send({ subscription: id, observations: [{ channel: name, state, value }] });
        if (family.append) { streamLogs(sub, rest, update); return; }
        const read = async () => {
          if (sub.stopped) return;
          const reads = family.reads, single = reads.length === 1;
          const replies = await Promise.all(reads.map((op) => reply(op, rest, socketUrl)));
          if (sub.stopped) return;
          const ok = replies.every((item) => item.outcome === 'applied');
          // Keyed by op name, as the panel's own family registry delivers a family of several reads.
          const value = !ok ? null : !single ? Object.fromEntries(reads.map((op, i) => [op, replies[i].result])) : replies[0].result;
          const text = JSON.stringify([ok, value]);
          if (text !== sub.last) { sub.last = text; update(value, ok ? 'known' : 'unavailable'); }
          sub.timer = setTimeout(read, floors[name] ?? family.floor);
        };
        read();
        return;
      }
      if (message.op === 'unsubscribe') {
        const sub = subscriptions.get(args.subscription);
        if (sub) { sub.stopped = true; clearTimeout(sub.timer); if (sub.abort) sub.abort.abort(); subscriptions.delete(args.subscription); }
        send({ command_id: id, outcome: sub ? 'applied' : 'refused', ...(sub ? {} : { code: 'invalid_value' }) });
        return;
      }
      const answered = await reply(message.op, message.op === 'settings' && message.settings ? message.settings : args, socketUrl);
      send({ command_id: id, ...answered });
    }, () => {
      for (const sub of subscriptions.values()) { sub.stopped = true; clearTimeout(sub.timer); if (sub.abort) sub.abort.abort(); }
      subscriptions.clear();
      if (record && session && session.length) writeFileSync(record, JSON.stringify(session, null, 1) + '\n');
    });

    // The logs family: the fixture's event stream, forwarded as appended line arrays.
    async function streamLogs(sub, args, update) {
      const http = tables.httpRequestFor('logs.stream', args);
      const answered = await answer({ method: 'GET', url: http.url, headers: {}, socketUrl });
      const lines = String(answered.body || '').split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).replace(/^ /, ''));
      if (!sub.stopped) update(lines);
    }
  });

  return { log, close: () => new Promise((done) => blobServer.close(done)) };
}

/** An `answer` for fixtures with a real HTTP server: ask that server, as the page named by the door URL. */
export function loopbackAnswer(server) {
  return async ({ method, url, headers, body, socketUrl }) => {
    if (!server.listening) return { status: 503, type: 'application/json', body: '{}' };
    const origin = `http://127.0.0.1:${server.address().port}`;
    const door = new URL(socketUrl, origin);
    const page = door.searchParams.get('page') || '/';
    const referer = new URL(page, origin);
    for (const key of ['lang', 'theme', 'embed']) if (door.searchParams.has(key)) referer.searchParams.set(key, door.searchParams.get(key));
    const response = await fetch(new URL(url, origin + '/'), { method, headers: { ...headers, referer: referer.href }, body });
    const type = response.headers.get('content-type') || '';
    const headersOut = {};
    response.headers.forEach((value, name) => { if (name.startsWith('x-')) headersOut[name] = value; });
    const isText = /json|^text\//.test(type) && !/event-stream/.test(type) ? true : /event-stream/.test(type);
    return { status: response.status, type, body: isText ? await response.text() : Buffer.from(await response.arrayBuffer()), headers: Object.keys(headersOut).length ? headersOut : undefined };
  };
}
