import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

/**
 * The real page transport (assets/transport.js), loaded the way the page shell loads it: after `window`
 * and `document` exist and before the page script, from the page script's own assets directory. Its HTTP
 * implementation then drives the harness's fake `fetch` exactly as the page does in a browser.
 */
export function loadTransport(pageScript) {
  const file = path.join(path.dirname(pageScript), 'transport.js');
  vm.runInThisContext(fs.readFileSync(file, 'utf8'), { filename: file });
}
