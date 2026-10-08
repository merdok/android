// HaNotice.row (i18n.js) and the server's notice() (http/Notice.kt) must draw the same row, so a
// banner looks the same whether the server painted it at first load or a script redrew it later.
// The expected markup is written out here once; the browser half renders it for real, and the server
// half checks that Notice.kt still emits each piece of that template, so a change to either fails.
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { chromium } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const chrome = process.env.CHROME || '/usr/bin/chromium';
const kotlin = await readFile(new URL('../../app/src/main/kotlin/io/panelassistant/android/http/Notice.kt', import.meta.url), 'utf8');

// [severity, title, detail, action] → the markup both renderers must produce (English catalogue).
const cases = [
  [['action_needed', 'Database storage failure', 'Recovery is not yet verified.', 'Open logs'],
    '<div class="notice" data-severity="action_needed"><span class="notice-pill">Action needed</span><span class="notice-text"><b class="notice-title">Database storage failure</b> <span class="notice-detail">Recovery is not yet verified.</span></span><span class="notice-action">Open logs</span></div>'],
  [['warning', 'Panel Assistant is required', '', ''],
    '<div class="notice" data-severity="warning"><span class="notice-pill">Warning</span><span class="notice-text"><b class="notice-title">Panel Assistant is required</b></span></div>'],
  [['note', '', 'Found on your network: panel.example.', ''],
    '<div class="notice" data-severity="note"><span class="notice-pill">Note</span><span class="notice-text"><span class="notice-detail">Found on your network: panel.example.</span></span></div>'],
];

test('server notice() still emits the template the parity cases encode', () => {
  for (const fragment of [
    '<div class="notice" data-severity="${severity.wire}"$attrs>',
    '<span class="notice-pill">${esc(strings.get(severity.wordKey))}</span>',
    '<span class="notice-text">',
    'if (title.isNotEmpty()) append("""<b class="notice-title">$title</b>""")',
    "if (title.isNotEmpty() && detail.isNotEmpty()) append(' ')",
    'if (detail.isNotEmpty()) append("""<span class="notice-detail">$detail</span>""")',
    'append("</span>")',
    'if (action.isNotEmpty()) append("""<span class="notice-action">$action</span>""")',
    'append("</div>")',
  ]) assert.ok(kotlin.includes(fragment), `Notice.kt no longer emits: ${fragment}`);
  // The order of the appends is the order of the markup.
  const order = ['notice-pill', 'notice-text">', 'notice-title', "append(' ')", 'notice-detail', 'append("</span>")', 'notice-action', 'append("</div>")'];
  const positions = order.map((piece) => kotlin.indexOf(piece));
  assert.deepEqual([...positions].sort((a, b) => a - b), positions, 'Notice.kt appends in template order');
});

test('HaNotice.row draws the same markup as the server notice()', { skip: !existsSync(chrome) && `no Chromium at ${chrome}` }, async (t) => {
  const browser = await chromium.launch({ headless: true, executablePath: chrome, args: ['--no-sandbox'] });
  t.after(() => browser.close());
  const page = await browser.newPage();
  await page.setContent(`<!doctype html><html lang="en"><head>${i18nBridge()}</head><body></body></html>`);
  for (const [args, expected] of cases) {
    const html = await page.evaluate((a) => window.HaNotice.row(...a).outerHTML, args);
    assert.equal(html, expected, `HaNotice.row(${JSON.stringify(args)})`);
  }
});

// The shared pill rule (info.css .pill/.notice-pill) must leave a row's quietness to the row: an
// Unavailable or Resolved row is dim as a whole, pill included, and only the five that ask for
// attention carry the accent dot.
test('a notice pill takes its row\'s colour and the dot marks only the severities that ask for attention', { skip: !existsSync(chrome) && `no Chromium at ${chrome}` }, async (t) => {
  const css = await readFile(new URL('../../app/src/main/assets/info.css', import.meta.url), 'utf8');
  const browser = await chromium.launch({ headless: true, executablePath: chrome, args: ['--no-sandbox'] });
  t.after(() => browser.close());
  const page = await browser.newPage();
  await page.setContent(`<!doctype html><html lang="en"><head><style>${css}</style>${i18nBridge()}</head><body></body></html>`);
  const severities = ['action_needed', 'warning', 'working', 'update', 'note', 'unavailable', 'resolved'];
  const seen = await page.evaluate((list) => list.map((severity) => {
    const row = document.body.appendChild(window.HaNotice.row(severity, 'Title', 'Detail', ''));
    const pill = row.querySelector('.notice-pill');
    return { severity, row: getComputedStyle(row).color, pill: getComputedStyle(pill).color, dot: getComputedStyle(pill, '::before').display !== 'none' };
  }), severities);
  for (const { severity, row, pill, dot } of seen) {
    assert.equal(pill, row, `${severity}: the pill word is the row's colour`);
    assert.equal(dot, !['unavailable', 'resolved'].includes(severity), `${severity}: accent dot`);
  }
  assert.notEqual(seen.find((s) => s.severity === 'resolved').row, seen.find((s) => s.severity === 'warning').row, 'a resolved row is quieter than a warning');
});
