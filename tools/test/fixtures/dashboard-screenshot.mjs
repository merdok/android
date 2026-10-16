// The Dashboard's screenshot card as a page fixture, and the routes that answer it: shared by the screenshot
// tests and the Dashboard tests that need a full card wall.
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { i18nBridge } from './i18n-bridge.mjs';
import { json, root } from './browser-harness.mjs';
import { initialScreenshot, freshScreenshot } from './screenshot-images.mjs';

export function screenshotFixture({ hardened = false, supported = true, locale = 'en' } = {}) {
  const dialogShim = supported ? '' : '<script>HTMLDialogElement.prototype.showModal=undefined;</script>';
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge({ locale })}</head><body data-hardened="${hardened ? '1' : '0'}">
    <canvas id="perfchart" width="600" height="96"></canvas><canvas id="respchart" width="600" height="150"></canvas>
    <table id="perf"></table><table id="smtbl"></table><table id="streamtbl"></table>
    <h2>Top processes <span class="seg top-process-modes" role="group" aria-label="Rank processes by"><button type="button" class="top-process-mode" data-mode="cpu" aria-pressed="true" onclick="setTopMode('cpu')">CPU</button><button type="button" class="top-process-mode" data-mode="ram" aria-pressed="false" onclick="setTopMode('ram')">RAM</button></span></h2>
    <table id="topproc"></table><table id="noisyentities"></table>
    <small id="smhdr"></small><small id="perfage"></small><small id="sensage"></small><small id="insthdr"></small>
    <table id="senstbl"></table><p id="insthint"></p><div id="ctlzone"></div>
    <div id="dashboard-cards"><div class="card" id="shotcard" data-capture-ok="0"><h2>Screenshot</h2>
      <a class="shot" href="/api/v1/screenshot.png" target="_blank" rel="noopener" style="aspect-ratio:16/9">
        <img src="/initial.png" alt="panel screenshot" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')">
      </a></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>${dialogShim}<script src="/info.js"></script></body></html>`;
}

export async function requestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

export function screenshotRoutes(options = {}) {
  const calls = options.calls || [];
  const fresh = freshScreenshot;
  return async (path, request) => {
    if (path === '/info.css') return { headers: { 'content-type': 'text/css' }, body: await readFile(join(root, 'info.css')) };
    if (path === '/initial.png') return { headers: { 'content-type': 'image/png' }, body: initialScreenshot };
    if (path === '/api/v1/input') {
      calls.push({ method: request.method, path, body: await requestBody(request), contentType: request.headers['content-type'] });
      if (options.input) return options.input(path, request, fresh);
      return { status: 200, headers: {
        'content-type': 'image/png',
        'X-ha-paneld-Input-Id': 'tap-17',
        'X-ha-paneld-Input-Route': 'accessibility',
        'X-ha-paneld-Screenshot-Route': 'daemon',
        'X-ha-paneld-Screenshot-Id': 'a'.repeat(64),
      }, body: await fresh };
    }
    if (path === '/api/v1/screenshot.png') {
      calls.push({ method: request.method, path });
      return options.screenshot ? options.screenshot(path, request, fresh) : { status: 200, headers: { 'content-type': 'image/png' }, body: await fresh };
    }
    if (path === '/api/v1/perf') return json(options.perf || {});
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  };
}
