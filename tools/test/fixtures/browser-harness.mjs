// Shared stand-in for the panel's :8888 listener used by the browser behaviour tests: it serves the real
// page assets from app/src/main/assets and answers every other path through the test's own routes.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

export const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
export const chrome = process.env.CHROME || '/usr/bin/chromium';

export const browserTest = existsSync(chrome) ? test : test.skip;

export function json(body, status = 200) {
  return { status, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

// assetDelayMs models a real panel, where a referenced script arrives some time after the document. On
// localhost every asset is instant, which hides whether a script runs before or after first paint.
export async function startHarness(routes, pageFixture, assetDelayMs = 0) {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/') return response.end(pageFixture());
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js', '/proximity-learning.js', '/install.js', '/info.js', '/power-safety.js'].includes(path)) {
      response.setHeader('content-type', 'application/javascript; charset=utf-8');
      return response.end(await readFile(join(root, path.slice(1)), 'utf8'));
    }
    if (path.startsWith('/assets/') && path.endsWith('.js')) {
      response.setHeader('content-type', 'application/javascript; charset=utf-8');
      const body = await readFile(join(root, path.slice('/assets/'.length)), 'utf8');
      if (assetDelayMs) await new Promise((resolve) => setTimeout(resolve, assetDelayMs));
      return response.end(body);
    }
    if (path === '/info.css') {
      response.setHeader('content-type', 'text/css');
      return response.end(await readFile(join(root, 'info.css'), 'utf8'));
    }
    const result = await routes(path, request);
    response.writeHead(result?.status || (result ? 200 : 404), result?.headers);
    response.end(result?.body || 'not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

export function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}
