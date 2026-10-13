// The pages that follow composite subscription families (entities, install, setup) run without a page error
// over the stub's socket, in Chromium and WebKit. The fake door answers each family exactly as the panel's
// family registry does, so a page reading the wrong keys of a family value fails here.
import assert from 'node:assert/strict';
import test from 'node:test';
import { launch, loadCatalogues, startLayoutServer } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';

const { catalogues, locales } = await loadCatalogues();
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
test.after(() => new Promise((done) => { server.close(done); server.closeAllConnections(); }));

for (const browserName of ['chromium', 'webkit']) {
  test(`entities, install and setup run over the socket without a page error in ${browserName}`, async (t) => {
    const browser = await launch(browserName);
    t.after(() => browser.close());
    for (const name of ['entities', 'install', 'setup']) {
      const pageDef = PAGES.find((page) => page.name === name);
      const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      try {
        await page.goto(`${origin}${pageDef.path}?lang=en&theme=light`);
        assert.equal(await page.evaluate(() => document.body.hasAttribute('data-ui-socket')), true, `${name} uses the socket`);
        if (pageDef.ready) await pageDef.ready(page.mainFrame());
        // Past the first delivery of every family the page follows.
        await page.waitForTimeout(1500);
        assert.deepEqual(errors, [], `${name} in ${browserName}`);
      } finally {
        await page.close();
      }
    }
  });
}
