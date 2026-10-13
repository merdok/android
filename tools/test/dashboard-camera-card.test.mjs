import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';
import { browserTest, chrome, json, startHarness } from './fixtures/browser-harness.mjs';

// ---------------------------------------------------------------------------------------------
// Dashboard camera-stream card. The card answers one question — is the panel delivering the stream
// it was asked for — so these cover what it says when it does not know yet, when it does, and when
// the facts go away. A verdict that survives a gap in the facts is the defect they exist to catch.

function cameraFixture(translations = {}) {
  return `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css">${i18nBridge({ strings: translations })}</head>
    <body data-hydrate="0" data-hardened="0">
    <canvas id="perfchart" width="600" height="96"></canvas><canvas id="respchart" width="600" height="150"></canvas>
    <table id="perf"></table><table id="smtbl"></table><table id="streamtbl"></table><table id="topproc"></table>
    <table id="noisyentities"></table><small id="smhdr"></small><small id="perfage"></small><small id="sensage"></small>
    <small id="insthdr"></small><table id="senstbl"></table><p id="insthint"></p><div id="ctlzone"></div>
    <div id="dashboard-cards"><div class="card" data-layout-key="camera-stream">
      <h2>Camera stream <small id="camhdr"></small></h2>
      <table id="camtbl"><tr><td style="color:#888">reading…</td></tr></table></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/info.js"></script></body></html>`;
}

// A board whose profile declares no camera: the server omits the card entirely, so nothing here mounts.
function cameralessFixture() {
  return cameraFixture().replace(/<div class="card" data-layout-key="camera-stream">[\s\S]*?<\/div><\/div>/, '</div>');
}

// The live projection, exactly as CameraPresentation.statusJson() emits it.
function cameraStatus(overrides = {}) {
  return {
    state: 'live', outcome: 'ok', fault: 'none', fault_detail: null, recovery: 'none',
    clients: 1, last_frame_age_ms: 40, consecutive_failures: 0, indication: 'overlay', live: true,
    stream_clients: 1, stream_port: 8554, encoder: 'c2.rk.avc.encoder',
    encode_width: 1280, encode_height: 720, encode_fps: 15, encode_kbps: 2000,
    delivered_fps: 14.8, delivered_kbps: 1660,
    snapshot_exposure: 'converged', snapshot_exposure_fallbacks: 0,
    summary: 'camera open for 1 client (1 streaming); stream listening on port 8554', action: 'none',
    ...overrides,
  };
}

const cameraIdle = cameraStatus({
  state: 'idle', live: false, clients: 0, stream_clients: 0, encoder: null,
  encode_width: null, encode_height: null, encode_fps: null, encode_kbps: null,
  delivered_fps: null, delivered_kbps: null, indication: 'none',
  summary: 'camera closed; nobody is watching; stream listening on port 8554',
});
const cameraShort = cameraStatus({ clients: 1, stream_clients: 1, delivered_fps: 10.05, delivered_kbps: 1660 });

// `state.payload` is what the panel answers with right now; a test moves the panel by assigning to it.
// 'unreachable' makes the route fail, which is how a poll loses its facts on a real panel.
// `delayNth`/`delayMs` hold one answer back, which is how a real poll loses its place: the response
// still arrives, just long after the state it describes. `hideable` makes document.hidden settable so
// a backgrounded tab can be modelled. `state.overlaps` counts requests that arrived while another was
// still open — it must stay at zero, which is the single-flight guarantee stated as a number.
async function startCameraHarness(t, payload, pageFixture = cameraFixture, options = {}) {
  const state = { payload, overlaps: 0 };
  const served = [];
  let open = 0;
  const harness = await startHarness(async (path) => {
    if (path !== '/api/v1/camera/status') return null;
    if (open > 0) state.overlaps++;
    open++;
    try {
      served.push(state.payload);
      if (options.delayNth && served.length === options.delayNth) {
        await new Promise((resolve) => setTimeout(resolve, options.delayMs));
      }
      if (state.payload === 'unreachable') {
        return { status: 503, headers: { 'content-type': 'text/plain' }, body: 'camera-status-unavailable\n' };
      }
      // A live panel's frame age moves on every reading, so each reading is a new value for the
      // camera family (which delivers on change); the age stays inside its stalled/not-stalled side.
      const reading = state.payload && typeof state.payload.last_frame_age_ms === 'number'
        ? { ...state.payload, last_frame_age_ms: state.payload.last_frame_age_ms + (served.length % 7) } : state.payload;
      return json(reading);
    } finally {
      open--;
    }
  }, pageFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(4_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  if (options.hideable) {
    await page.addInitScript(() => {
      window.__hidden = false;
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__hidden });
    });
  }
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  return { page, state, served };
}

// Wait on what the panel was actually asked, rather than on wall-clock time, so a slow machine
// changes how long a test takes and never what it proves.
async function untilServed(served, count) {
  const deadline = Date.now() + 40_000;
  while (served.length < count) {
    if (Date.now() > deadline) throw new Error(`only ${served.length} status polls served, wanted ${count}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

const cameraText = (page) => page.evaluate(() => document.getElementById('camtbl').textContent);
const cameraHeader = (page) => page.evaluate(() => document.getElementById('camhdr').textContent);
// The verdict needs three consecutive readings, two seconds apart, so anything it decides is ~6s away.
const untilCamera = (page, needle) => page.waitForFunction(
  (text) => document.getElementById('camtbl').textContent.includes(text), needle, { timeout: 20_000 },
);

browserTest('Camera runtime rows render the selected catalogue instead of stale English', async (t) => {
  const translations = {
    'dashboard.camera.label.session': '会话',
    'dashboard.camera.session.closed': '已关闭',
    'dashboard.camera.watchers.none': '无人观看',
    'dashboard.camera.label.encoding': '编码',
    'dashboard.camera.encoding.nothing': '当前未编码',
    'dashboard.camera.encoding.idle_cost': '· 空闲摄像头不消耗面板资源',
  };
  const { page } = await startCameraHarness(t, cameraIdle, () => cameraFixture(translations));

  await untilCamera(page, '当前未编码');
  const text = await cameraText(page);
  assert.match(text, /会话/);
  assert.match(text, /已关闭/);
  assert.match(text, /无人观看/);
  assert.match(text, /编码/);
  assert.match(text, /空闲摄像头不消耗面板资源/);
  assert.doesNotMatch(text, /Session|Encoding|nothing is being encoded|nobody is watching/);
  assert.equal(await cameraHeader(page), '· 已关闭');
});

browserTest('An idle camera reports that nothing is encoding, and offers no rate to judge', async (t) => {
  const { page } = await startCameraHarness(t, cameraIdle);

  await untilCamera(page, 'nothing is being encoded');
  const text = await cameraText(page);
  assert.match(text, /closed/, 'the session row names the closed session');
  assert.match(text, /nobody is watching/);
  assert.match(text, /an idle camera costs the panel nothing/, 'idle is zero cost and the card says so');
  assert.equal(text.includes('Frame rate'), false, 'there is no delivered rate while nothing encodes');
  assert.equal(text.includes('Delivery'), false, 'a closed camera is not delivering badly — it is not delivering');
  assert.equal(await cameraHeader(page), '· closed');
});

browserTest('A starting stream measures before it judges, then confirms healthy delivery for every client', async (t) => {
  const { page, state } = await startCameraHarness(t, cameraStatus({
    clients: 2, stream_clients: 2, delivered_fps: null, delivered_kbps: null,
  }));

  await untilCamera(page, 'starting…');
  const starting = await cameraText(page);
  assert.match(starting, /c2\.rk\.avc\.encoder/, 'the encoder in use is named');
  assert.match(starting, /1280×720/);
  assert.match(starting, /2 clients, 2 streaming/, 'every subscriber is counted, streams among them');
  assert.match(starting, /asked for 15 fps/, 'the request is shown before anything has been delivered');
  assert.match(starting, /measuring…/, 'no verdict is offered from no readings');
  assert.equal(await cameraHeader(page), '· starting');

  state.payload = cameraStatus({ clients: 2, stream_clients: 2 });
  await untilCamera(page, 'delivering what it was asked for');
  const healthy = await cameraText(page);
  assert.match(healthy, /14\.8 of 15 fps/, 'delivered is shown against requested, not alone');
  assert.match(healthy, /1660 of 2000 kbps cap/);
  assert.match(healthy, /under the cap is normal for a still scene/, 'a bitrate under its cap is never a fault');
  assert.equal(await cameraHeader(page), '· 14.8 of 15 fps');
});

browserTest('A sustained shortfall is named and made actionable, and clears only once it is re-earned', async (t) => {
  const { page, state, served } = await startCameraHarness(t, cameraShort);

  // The measured WF1589T case: 10 fps against a configured 15. One reading is not yet a verdict.
  await untilCamera(page, '10.1 of 15 fps');
  assert.match(await cameraText(page), /measuring…/, 'a single short reading must not raise a verdict');

  await untilCamera(page, 'not keeping up');
  const short = await cameraText(page);
  assert.match(short, /lower the frame rate or the resolution on Configure → Camera/, 'the shortfall is actionable');
  assert.match(short, /accept the rate this panel is giving/, 'the request is never clamped for the operator');
  assert.match(short, /why it is short is not established here/, 'no cause is claimed for it');
  assert.match(short, /10\.1 of 15 fps/, 'the request stands at 15 — nothing rewrites it downward');

  const decided = served.length;
  state.payload = cameraStatus();
  await untilCamera(page, 'delivering what it was asked for');
  assert.equal(served.length - decided >= 3, true, 'a recovery is trusted no faster than the fault was');
});

browserTest('Facts the panel will not give are reported as unavailable, never as a rate', async (t) => {
  const { page, state } = await startCameraHarness(t, 'unreachable');

  await untilCamera(page, 'status unavailable');
  assert.match(await cameraText(page), /the panel did not answer/);
  assert.equal(await cameraHeader(page), '· unavailable');

  state.payload = cameraStatus({ encode_fps: null, encode_kbps: null, delivered_fps: null, delivered_kbps: null });
  await untilCamera(page, 'requested rate unavailable');
  const partial = await cameraText(page);
  assert.match(partial, /cap unavailable/, 'a missing cap is missing, not zero');
  assert.match(partial, /measuring…/, 'nothing is judged from facts that are not there');

  state.payload = cameraStatus({ encode_fps: null, encode_kbps: null });
  await untilCamera(page, '14.8 fps delivered');
  assert.match(await cameraText(page), /measuring…/, 'a delivered rate alone cannot be short of anything');
});

browserTest('A verdict does not survive a gap in the facts', async (t) => {
  const { page, state, served } = await startCameraHarness(t, cameraShort);

  await untilCamera(page, 'not keeping up');
  state.payload = 'unreachable';
  await untilCamera(page, 'status unavailable');
  assert.equal((await cameraText(page)).includes('not keeping up'), false, 'the stale verdict goes with the stale facts');

  state.payload = cameraShort;
  await untilCamera(page, '10.1 of 15 fps');
  const resumed = served.length;
  await untilCamera(page, 'not keeping up');
  assert.equal(served.length - resumed >= 2, true, 'the run restarts from zero rather than resuming where it left off');
});

browserTest('A panel with no camera card never polls for one, and never stalls the remembered card sizes', async (t) => {
  const { page, served } = await startCameraHarness(t, cameraIdle, cameralessFixture);

  await page.waitForFunction(() => window.cardSizeSources && window.cardSizeSources.camera === true);
  assert.equal(await page.evaluate(() => !!document.getElementById('camtbl')), false, 'the fixture has no camera card');
  assert.equal(served.length, 0, 'a board with no camera asks the camera route nothing');
});

browserTest('A camera held open for a snapshot is not called idle, and is not called free', async (t) => {
  const { page } = await startCameraHarness(t, cameraStatus({
    clients: 1, stream_clients: 0, encoder: null, encode_width: null, encode_height: null,
    encode_fps: null, encode_kbps: null, delivered_fps: null, delivered_kbps: null,
  }));

  await untilCamera(page, 'no stream is encoding');
  const text = await cameraText(page);
  assert.match(text, /open · 1 client/, 'the session is open, and one subscriber is holding it');
  assert.match(text, /the camera is open for a snapshot/, 'the reason nothing is encoding is named');
  assert.equal(text.includes('costs the panel nothing'), false,
    'a snapshot client pays for every frame it converts — only a closed camera is free');
  assert.equal(await cameraHeader(page), '· open');
});

browserTest('A stream that stops delivering says so, rather than starting for ever', async (t) => {
  const { page, state } = await startCameraHarness(t, cameraStatus({
    delivered_fps: null, delivered_kbps: null, last_frame_age_ms: 30_000,
  }));

  // The delivered rate is absent exactly as it is before the first frames arrive, so a card that read
  // both the same way would show a broken stream as a warming-up one for as long as it stayed broken.
  await untilCamera(page, 'no frames for 30s');
  const stalled = await cameraText(page);
  assert.equal(stalled.includes('starting…'), false, 'a stalled stream is not a starting one');
  assert.match(stalled, /not delivering/, 'the verdict says what is happening, not that it is measuring');
  assert.match(stalled, /the encoder is bound to the session but no frames are arriving/);

  state.payload = cameraStatus({
    state: 'degraded', fault: 'stream_encoder', consecutive_failures: 3,
    delivered_fps: null, delivered_kbps: null, last_frame_age_ms: null,
    action: 'no hardware H.264 encoder fits the camera bitrate cap; snapshots still work, streaming does not',
  });
  await untilCamera(page, 'stopped — stream encoder');
  const faulted = await cameraText(page);
  assert.match(faulted, /stopped retrying after 3 failures/, 'the session row carries the panel’s own account');
  assert.match(faulted, /no hardware H\.264 encoder fits the camera bitrate cap/, 'the panel’s action is passed through');
});

browserTest('A bitrate over its cap is not called normal', async (t) => {
  // Measured on a real WF1589T at 1080p: the encoder delivered 4817 kbps against a 2000 kbps cap
  // while the frame rate was met exactly, so the healthy-looking case is precisely where this shows.
  const { page } = await startCameraHarness(t, cameraStatus({
    encode_width: 1920, encode_height: 1080, delivered_fps: 15.04, delivered_kbps: 4817,
  }));

  await untilCamera(page, '4817 of 2000 kbps cap');
  const text = await cameraText(page);
  assert.match(text, /over the cap it was given/, 'an overshoot is named as an overshoot');
  assert.equal(text.includes('under the cap is normal'), false, 'the reassuring line must not print over a fault');
  assert.match(text, /15 of 15 fps/, 'the frame rate is judged on its own and is met here');
});

// Four cases about what the card says when its own evidence is imperfect: the rate it judges against
// is not the one the encoder was given, the state it is in is ambiguous, or the readings it has are
// not as consecutive as the counter thinks. Each is written against a defect that was really there.

browserTest('A stream is judged against what it asked for, not against a rate a snapshot already bound', async (t) => {
  // The camera session's capture rate is fixed by whoever opens it. A snapshot opens it at the
  // configured 15, a stream then asks the URL for 30 and joins that session rather than reconfiguring
  // it, so the encoder is bound to 15 while the person is still waiting for 30. Reporting "15 of 15"
  // there would answer their question with the wrong number and call a shortfall a success.
  const { page } = await startCameraHarness(t, cameraStatus({
    clients: 2, stream_clients: 1, requested_fps: 30, encode_fps: 15,
    delivered_fps: 14.9, delivered_kbps: 1660,
  }));

  await untilCamera(page, '30 fps asked for');
  const text = await cameraText(page);
  assert.match(text, /the session is bound to 15 fps, set when the camera was opened/,
    'the bound rate is shown beside the request rather than in place of it');
  assert.match(text, /14\.9 of 30 fps/, 'the delivered rate is measured against the request');
  assert.equal(text.includes('of 15 fps'), false, 'the bound rate must never become the target');
  assert.equal(await cameraHeader(page), '· 14.9 of 30 fps');

  await untilCamera(page, 'not keeping up');
});

browserTest('A stream waiting for its encoder is called a stream, not a snapshot', async (t) => {
  const { page } = await startCameraHarness(t, cameraStatus({
    clients: 1, stream_clients: 1, requested_fps: 30, encoder: null,
    encode_width: null, encode_height: null, encode_fps: null, encode_kbps: null,
    delivered_fps: null, delivered_kbps: null,
  }));

  await untilCamera(page, 'waiting for the encoder');
  const text = await cameraText(page);
  assert.match(text, /a stream asked for 30 fps and the encoder has not started yet/);
  assert.equal(text.includes('open for a snapshot'), false,
    'a stream client is present, so this is not a snapshot holding the camera');
});

browserTest('A response that crawls back does not build a verdict across the gap it left', async (t) => {
  const { page, state, served } = await startCameraHarness(t, cameraShort, cameraFixture, {
    // Long enough to exceed the run's maximum gap, so the readings either side of it are not
    // consecutive and the run that was building must start again rather than resume.
    delayNth: 2, delayMs: 8_000,
  });

  await untilCamera(page, '10.1 of 15 fps');
  await untilServed(served, 3);
  assert.match(await cameraText(page), /measuring…/, 'a run broken by a slow answer is not a run');

  await untilCamera(page, 'not keeping up');
  // The arithmetic is the point. Readings one and two straddle the eight-second gap; a card that
  // counted them as consecutive would have reached three on the reading right after it, and the run
  // would already span far more than the window. Discarding them pushes the verdict past that.
  assert.equal(served.length >= 4, true,
    'a verdict built across the gap would have landed on the third reading');
  assert.equal(state.overlaps, 0, 'the card never had two reads of itself in flight at once');
});

browserTest('A hidden tab stops polling, and the gap it leaves is not counted as evidence', async (t) => {
  const { page, served } = await startCameraHarness(t, cameraShort, cameraFixture, { hideable: true });

  await untilCamera(page, '10.1 of 15 fps');
  await page.evaluate(() => { window.__hidden = true; document.dispatchEvent(new Event('visibilitychange')); });
  const parked = served.length;
  await page.waitForTimeout(7_000);
  assert.equal(served.length, parked, 'a hidden tab must not keep asking the panel for status');

  await page.evaluate(() => { window.__hidden = false; document.dispatchEvent(new Event('visibilitychange')); });
  const resumed = served.length;
  await untilCamera(page, 'not keeping up');
  assert.equal(served.length - resumed >= 3, true,
    'the readings either side of the hidden gap are not consecutive, so the run starts again');
});

browserTest('A sustained verdict is not promoted from readings that cannot span the measurement window', async (t) => {
  // The panel measures delivery over a rolling five seconds. Three polls two seconds apart cover
  // about four, so a count alone would promote "sustained" from three readings drawn from one
  // window's data. The verdict must also have taken longer than that window to reach.
  const { page } = await startCameraHarness(t, cameraShort);

  await untilCamera(page, '10.1 of 15 fps');
  const firstReadingAt = Date.now();
  await untilCamera(page, 'not keeping up');
  assert.equal(Date.now() - firstReadingAt >= 5_000, true,
    'the run must span more than the panel’s own five-second delivery window');
});

browserTest('Two readings that already span the window are still not enough to judge on', async (t) => {
  // The elapsed test and the count test are not the same test, and each has a case the other misses.
  // Here a slow answer leaves two readings five and a half seconds apart: long enough to span the
  // panel's measurement window, and still only two samples. A verdict from two readings is thinner
  // evidence than the card claims to be reporting, so it waits for the third.
  const { page, state, served } = await startCameraHarness(t, cameraShort, cameraFixture, {
    delayNth: 2, delayMs: 3_500,
  });

  await untilCamera(page, '10.1 of 15 fps');
  await untilServed(served, 2);
  // The delayed answer is chosen while it is held, so its arrival is visible in the card's own text
  // and the assertion below cannot land on the wrong reading.
  state.payload = cameraStatus({ clients: 1, stream_clients: 1, delivered_fps: 10.24, delivered_kbps: 1660 });
  await untilCamera(page, '10.2 of 15 fps');
  assert.match(await cameraText(page), /measuring…/,
    'two readings spanning the window are still two readings');

  await untilCamera(page, 'not keeping up');
});
