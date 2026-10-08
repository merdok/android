#!/usr/bin/env python3
"""Exercise brand sync/check through its CLI, including actual HTTP publication drift."""
import functools
import http.server
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

REPO = Path(__file__).resolve().parents[2]


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class BrandSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'repo'
        self.source = Path(self.temp.name) / 'published'
        self.source.mkdir()
        (self.root / 'tools/brand').mkdir(parents=True)
        shutil.copy(REPO / 'tools/brand/sync.py', self.root / 'tools/brand/sync.py')
        manifest = json.loads((REPO / 'tools/brand/manifest.json').read_text())
        for asset in manifest['assets']:
            shutil.copy(REPO / asset['path'], self.source / asset['url'].rsplit('/', 1)[1])
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(QuietHandler, directory=str(self.source)))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        # Substitute the publication origin in the isolated CLI fixture so validation and HTTP
        # checks still run through the real command without depending on the external website.
        script = self.root / 'tools/brand/sync.py'
        script.write_text(script.read_text().replace('https://panel-assistant.io/brand/', f'http://127.0.0.1:{self.server.server_port}/'))
        for asset in manifest['assets']:
            asset['url'] = f'http://127.0.0.1:{self.server.server_port}/' + asset['url'].rsplit('/', 1)[1]
        (self.root / 'tools/brand/manifest.json').write_text(json.dumps(manifest))
        self.assertEqual(self.cli('sync', '--source-dir', str(self.source)).returncode, 0)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def cli(self, *args):
        return subprocess.run([sys.executable, str(self.root / 'tools/brand/sync.py'), *args], capture_output=True, text=True)

    def test_published_set_and_derivatives_pass(self):
        self.assertEqual(self.cli('check').returncode, 0)
        self.assertEqual(self.cli('check', '--offline').returncode, 0)
        name = (self.root / 'app/src/main/assets/name.svg').read_text()
        self.assertIn('fill="currentColor"', name)

    def test_edited_local_copy_and_derivative_fail(self):
        icon = self.root / 'app/src/main/assets/icon.svg'
        icon.write_bytes(icon.read_bytes() + b'\n')
        result = self.cli('check', '--offline')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('local copy differs', result.stderr)
        self.assertEqual(self.cli('sync', '--source-dir', str(self.source)).returncode, 0)
        vector = self.root / 'app/src/main/res/drawable/ic_pa_mark.xml'
        vector.write_bytes(vector.read_bytes() + b'\n')
        result = self.cli('check', '--offline')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('generated copy differs', result.stderr)

    def test_self_consistent_old_pin_fails_latest_check_and_sync_recovers(self):
        icon = self.source / 'icon-tight.svg'
        icon.write_bytes(icon.read_bytes().replace(b'#607D8B', b'#607D8C'))
        self.assertEqual(self.cli('check', '--offline').returncode, 0)
        result = self.cli('check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('manifest lags published asset', result.stderr)
        self.assertEqual(self.cli('sync').returncode, 0)
        self.assertEqual(self.cli('check').returncode, 0)

    def test_unavailable_publication_fails_without_rewriting_local_copies(self):
        icon = self.source / 'icon-tight.svg'
        icon.unlink()
        before = (self.root / 'tools/brand/manifest.json').read_bytes()
        self.assertNotEqual(self.cli('check').returncode, 0)
        self.assertNotEqual(self.cli('sync').returncode, 0)
        self.assertEqual((self.root / 'tools/brand/manifest.json').read_bytes(), before)
        self.assertEqual(self.cli('check', '--offline').returncode, 0)

    def test_manifest_cannot_redirect_the_freshness_authority(self):
        path = self.root / 'tools/brand/manifest.json'
        manifest = json.loads(path.read_text())
        manifest['assets'][0]['url'] = 'https://example.invalid/icon-tight.svg'
        path.write_text(json.dumps(manifest))
        result = self.cli('check', '--offline')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('noncanonical brand URL', result.stderr)


if __name__ == '__main__':
    unittest.main()
