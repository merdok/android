// The Dashboard's screenshot card: the overlay, taps through it, capture failures, and in the sidebar the last
// capture shown through the transport while a live one is taken.
import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';
import { browserTest, chrome, json, startHarness as startHarnessWith } from './fixtures/browser-harness.mjs';
import { initialScreenshot } from './fixtures/screenshot-images.mjs';
import { screenshotFixture, screenshotRoutes } from './fixtures/dashboard-screenshot.mjs';

const startHarness = (routes, pageFixture) => startHarnessWith(routes, pageFixture);

browserTest('Loaded screenshot opens a fitted modal and one exact click replaces both images', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 420 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  const link = page.locator('#shotcard .shot');
  await link.click();
  const dialog = page.locator('#screenshot-dialog');
  await dialog.waitFor({ state: 'visible' });
  await page.getByRole('dialog', { name: 'Screenshot · live panel' }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Close' }).evaluate((element) => document.activeElement === element), true);
  const headerLayout = await dialog.locator('.screenshot-dialog-bar').evaluate((bar) => {
    const title = bar.querySelector('h2').getBoundingClientRect();
    const refresh = bar.querySelector('.screenshot-dialog-refresh').getBoundingClientRect();
    const close = bar.querySelector('.screenshot-dialog-close').getBoundingClientRect();
    return { titleLeft: title.left, refreshLeft: refresh.left, closeLeft: close.left, closeRight: close.right, barRight: bar.getBoundingClientRect().right };
  });
  assert.ok(headerLayout.titleLeft < headerLayout.refreshLeft, `title should be left aligned: ${JSON.stringify(headerLayout)}`);
  assert.ok(headerLayout.refreshLeft < headerLayout.closeLeft, `Close should follow Refresh: ${JSON.stringify(headerLayout)}`);
  assert.ok(headerLayout.closeRight <= headerLayout.barRight, `Close should be rightmost: ${JSON.stringify(headerLayout)}`);
  const image = page.locator('#screenshot-dialog-image');
  const box = await image.boundingBox();
  assert.ok(box.width <= 478 && box.height <= 320, `unexpected modal image bounds ${JSON.stringify(box)}`);
  const natural = await image.evaluate((element) => ({ width: element.naturalWidth, height: element.naturalHeight }));
  const clientX = Math.floor(box.x + box.width * 0.37);
  const clientY = Math.floor(box.y + box.height * 0.61);
  const x = Math.floor((clientX - box.x) * natural.width / box.width);
  const y = Math.floor((clientY - box.y) * natural.height / box.height);
  await page.mouse.click(clientX, clientY);
  await page.locator('#screenshot-dialog-status').getByText('Screenshot updated.').waitFor();
  assert.deepEqual(calls.filter((call) => call.path === '/api/v1/input'), [
    { method: 'POST', path: '/api/v1/input', body: `x=${x}&y=${y}&capture=1`, contentType: 'application/x-www-form-urlencoded' },
  ]);
  assert.equal(await dialog.getAttribute('data-input-id'), 'tap-17');
  assert.equal(await dialog.getAttribute('data-input-route'), 'accessibility');
  assert.equal(await dialog.getAttribute('data-screenshot-route'), 'daemon');
  assert.equal(await dialog.getAttribute('data-screenshot-id'), 'a'.repeat(64));
  const cardSrc = await page.locator('#shotcard img').getAttribute('src');
  assert.match(cardSrc, /^blob:/);
  assert.equal(await image.getAttribute('src'), cardSrc);

  await page.getByRole('button', { name: 'Close' }).click();
  assert.equal(await link.evaluate((element) => document.activeElement === element), true);
  await link.click();
  assert.ok((await image.evaluate((element) => element.naturalWidth)) > 0);
  await page.keyboard.press('Escape');
  assert.equal(await link.evaluate((element) => document.activeElement === element), true);
});

browserTest('Keyboard activation opens the overlay without injecting a tap', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').focus();
  await page.keyboard.press('Enter');
  await page.locator('#screenshot-dialog').waitFor({ state: 'visible' });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(100);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
});

// In the sidebar the page has no cached image URL it can load itself (only the transport reaches the panel), so
// the last capture must come through the transport, or a failed or slow live capture leaves the card empty.
browserTest('In the sidebar a failed live capture still shows the last capture', async (t) => {
  const id = 'c'.repeat(64);
  const calls = [];
  const harness = await startHarness(screenshotRoutes({
    calls,
    screenshot: (path, request) => (new URL(request.url, 'http://panel.test').searchParams.get('cached') === id
      ? { status: 200, headers: { 'content-type': 'image/png' }, body: initialScreenshot }
      : { status: 503, headers: { 'content-type': 'text/plain' }, body: 'screenshot-unavailable\n' }),
  }), () => screenshotFixture()
    .replace('<body data-hardened="0">', '<body data-hardened="0" data-hydrate="1" data-ui-relay><div id="bannerzone"></div>')
    .replace('<img src="/initial.png" ', '<img '));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.route('**/api/v1/info*', (route) => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ banners: '', cards: {}, controls: '', shot: true, shotCached: `api/v1/screenshot.png?cached=${id}` }) }));
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.waitForFunction(() => {
    const shot = document.querySelector('#shotcard .shot'), image = shot && shot.querySelector('img');
    return !!(shot && shot.classList.contains('loaded') && image.complete && image.naturalWidth > 0);
  });
  assert.ok(calls.some((call) => call.path === '/api/v1/screenshot.png'), 'a live capture was asked for too');
});

// A warm render does not hydrate, so in the sidebar the card itself names the last capture.
browserTest('In the sidebar a warm Dashboard shows the last capture when the live one fails', async (t) => {
  const id = 'd'.repeat(64);
  const harness = await startHarness(screenshotRoutes({
    screenshot: (path, request) => (new URL(request.url, 'http://panel.test').searchParams.get('cached') === id
      ? { status: 200, headers: { 'content-type': 'image/png' }, body: initialScreenshot }
      : { status: 503, headers: { 'content-type': 'text/plain' }, body: 'screenshot-unavailable\n' }),
  }), () => screenshotFixture()
    .replace('<body data-hardened="0">', '<body data-hardened="0" data-ui-relay>')
    .replace('data-capture-ok="0">', `data-capture-ok="1" data-cached-shot="api/v1/screenshot.png?cached=${id}">`)
    .replace('<img src="/initial.png" ', '<img '));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.waitForFunction(() => {
    const shot = document.querySelector('#shotcard .shot'), image = shot && shot.querySelector('img');
    return !!(shot && shot.classList.contains('loaded') && image.complete && image.naturalWidth > 0);
  });
});

browserTest('Missing, modified and unsupported screenshot activations retain the ordinary link', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture({ supported: false }));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  const unsupportedPopupPromise = page.waitForEvent('popup');
  await page.locator('#shotcard .shot').click();
  const unsupportedPopup = await unsupportedPopupPromise;
  await unsupportedPopup.waitForLoadState('load');
  assert.equal(new URL(unsupportedPopup.url()).pathname, '/api/v1/screenshot.png');
  await unsupportedPopup.close();

  await page.evaluate(() => { HTMLDialogElement.prototype.showModal = function() { this.setAttribute('open', ''); }; setupScreenshotOverlay(); });
  await page.locator('#shotcard .shot').evaluate((element) => element.classList.remove('loaded'));
  const missingPopupPromise = page.waitForEvent('popup');
  await page.locator('#shotcard .shot').click();
  const missingPopup = await missingPopupPromise;
  await missingPopup.waitForLoadState('load');
  assert.equal(new URL(missingPopup.url()).pathname, '/api/v1/screenshot.png');
  await missingPopup.close();
  const fallback = await page.locator('#shotcard .shot').evaluate((element) => {
    const image = element.querySelector('img');element.classList.add('loaded');image.src='/initial.png';
    const modified = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true }); element.dispatchEvent(modified);
    return { modified: modified.defaultPrevented };
  });
  assert.deepEqual(fallback, { modified: false });
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
});

browserTest('Hardened mode opens a view-only overlay and refuses browser input', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture({ hardened: true }));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#screenshot-dialog-status').textContent(), 'View only — remote input is disabled in Hardened mode.');
  await page.locator('#screenshot-dialog-image').click({ position: { x: 5, y: 5 } });
  await page.waitForTimeout(100);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
});

browserTest('Tap admission failure retains the image and does not capture or retry', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls, input: async () => json({ error: 'remote-input-disabled' }, 403) }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  const original = await page.locator('#shotcard img').getAttribute('src');
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Remote input is disabled in Hardened mode.').waitFor();
  assert.equal(await page.locator('#shotcard img').getAttribute('src'), original);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 0);
});

browserTest('Confirmed capture failure performs one delayed safe GET and never retries the tap', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({
    calls,
    input: async () => ({ status: 503, headers: { 'content-type': 'application/json', 'X-ha-paneld-Input-Route': 'su' }, body: '{}' }),
  }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Screenshot updated.').waitFor();
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 1);
});

browserTest('Ambiguous network failure performs one safe screenshot GET without retrying input', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.evaluate(() => {
    const realFetch = window.fetch;
    window.inputAttempts = 0;
    window.fetch = (url, options) => {
      if (url === 'api/v1/input') { window.inputAttempts++; return Promise.reject(new TypeError('network unavailable')); }
      return realFetch(url, options);
    };
  });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Tap outcome unknown; current screenshot refreshed.').waitFor();
  assert.equal(await page.evaluate(() => window.inputAttempts), 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 1);
});

browserTest('An expired queued tap is reported as not accepted without a fallback capture', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({
    calls,
    input: async () => json({ ok: false, error: 'tap-expired' }, 504),
  }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Tap was not accepted.').waitFor();
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 0);
});

browserTest('Duplicate overlay clicks stay single-flight while the combined response is pending', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls, input: async (_path, _request, fresh) => {
    await new Promise((resolve) => setTimeout(resolve, 300));
    return { status: 200, headers: { 'content-type': 'image/png' }, body: await fresh };
  } }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  const image = page.locator('#screenshot-dialog-image');
  await image.dblclick({ position: { x: 9, y: 9 }, delay: 20 });
  await page.getByText('Screenshot updated.').waitFor();
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
});

browserTest('An older background capture cannot overwrite the post-tap screenshot', async (t) => {
  const calls = [];
  const oldCapture = initialScreenshot;
  const harness = await startHarness(screenshotRoutes({ calls, screenshot: async () => {
    await new Promise((resolve) => setTimeout(resolve, 350));
    return { status: 200, headers: { 'content-type': 'image/png' }, body: await oldCapture };
  } }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.evaluate(() => refreshScreenshot(document.getElementById('shotcard')));
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Screenshot updated.').waitFor();
  const postTapSize = await page.locator('#shotcard img').evaluate((image) => [image.naturalWidth, image.naturalHeight]);
  await page.waitForTimeout(450);
  assert.deepEqual(await page.locator('#shotcard img').evaluate((image) => [image.naturalWidth, image.naturalHeight]), postTapSize);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 1);
});
