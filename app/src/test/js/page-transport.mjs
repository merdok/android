import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';

/**
 * The real page transport (assets/transport.js), loaded the way the page shell loads it: after `window`
 * and `document` exist and before the page script, from the page script's own assets directory. Its HTTP
 * implementation then drives the harness's fake `fetch` exactly as the page does in a browser; fake answers
 * get a full Response shape from complete-fake-responses.cjs.
 */
export function loadTransport(pageScript) {
  createRequire(import.meta.url)('./complete-fake-responses.cjs');
  const file = path.join(path.dirname(pageScript), 'transport.js');
  vm.runInThisContext(fs.readFileSync(file, 'utf8'), { filename: file });
}
