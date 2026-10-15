// Profiles state badges: pills on one line keep a visible gap, and the strip keeps a margin below the
// toolbar card, at desktop and phone widths, in Chromium and WebKit (document-space measurement).
import assert from 'node:assert/strict';
import test from 'node:test';
import { launch, loadCatalogues, startLayoutServer } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';

const { catalogues, locales } = await loadCatalogues();
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
const profiles = PAGES.find((page) => page.name === 'profiles');
test.after(() => new Promise((done) => server.close(done)));

for (const browserName of ['chromium', 'webkit']) {
  test(`profile badges keep their spacing · ${browserName}`, { timeout: 300_000 }, async () => {
    const browser = await launch(browserName);
    try {
      const faults = [];
      for (const width of [1440, 390]) {
        const context = await browser.newContext({ viewport: { width, height: 900 } });
        const page = await context.newPage();
        await page.goto(`${origin}${profiles.path}?lang=en&theme=light`);
        await page.waitForFunction(() => document.querySelectorAll('#profile-badges > *').length >= 2);
        const m = await page.evaluate(() => {
          const doc = (n) => { const r = n.getBoundingClientRect(); return { l: r.left + scrollX, r: r.right + scrollX, t: r.top + scrollY, b: r.bottom + scrollY }; };
          const pills = [...document.querySelectorAll('#profile-badges > *')].filter((n) => n.getBoundingClientRect().width > 0).map(doc);
          const gaps = [];
          for (let i = 1; i < pills.length; i++) if (Math.abs(pills[i].t - pills[i - 1].t) < 2) gaps.push(Math.round(pills[i].l - pills[i - 1].r));
          return { gaps, above: Math.round(doc(document.getElementById('profile-badges')).t - doc(document.querySelector('.profile-toolbar')).b) };
        });
        if (!m.gaps.length) faults.push(`${width}: no same-line pair`);
        m.gaps.filter((g) => g < 6).forEach((g) => faults.push(`${width}: badge gap ${g}px`));
        if (m.above < 6) faults.push(`${width}: strip ${m.above}px below toolbar`);
        await context.close();
      }
      assert.deepEqual(faults, []);
    } finally {
      await browser.close();
    }
  });
}
