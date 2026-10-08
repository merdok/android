import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
PATHS = ["tools/tokens/tokens.py", "tools/tokens/tokens.json", "app/src/main/assets/info.css",
         "app/src/main/assets/api.html"]


class TokensTest(unittest.TestCase):
    """The generator, run on a copy of the repository files it reads and writes."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        for path in PATHS:
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(REPO / path, self.root / path)
        (self.root / "app/src/main/kotlin/io/panelassistant/android").mkdir(parents=True)

    def run_tokens(self, *args):
        return subprocess.run([sys.executable, str(self.root / "tools/tokens/tokens.py"), *args],
                              capture_output=True, text=True)

    def recolour(self, change):
        source = self.root / "tools/tokens/tokens.json"
        tokens = json.loads(source.read_text())
        change(tokens)
        source.write_text(json.dumps(tokens, indent=2) + "\n")

    def test_a_changed_value_reaches_every_output_and_check_catches_it_until_regenerated(self):
        self.assertEqual(self.run_tokens().returncode, 0)
        self.assertEqual(self.run_tokens("--check").returncode, 0)

        def change(tokens):
            tokens["web"]["themed"]["bg"]["light"] = "#abcdef"
            tokens["native"]["notice"]["fields"]["accent"]["dark"] = "#123456"
        self.recolour(change)
        stale = self.run_tokens("--check")
        self.assertEqual(stale.returncode, 1)
        for path in ["PanelTokens.kt", "tools/tokens/tokens.css", "app/src/main/assets/info.css",
                     "app/src/main/assets/api.html"]:
            self.assertIn(path, stale.stderr)

        self.assertEqual(self.run_tokens().returncode, 0)
        self.assertEqual(self.run_tokens("--check").returncode, 0)
        css = (self.root / "tools/tokens/tokens.css").read_text()
        self.assertIn("--bg:#abcdef;", css)
        kotlin = (self.root / "app/src/main/kotlin/io/panelassistant/android/PanelTokens.kt").read_text()
        self.assertIn("accent = 0xFF123456.toInt(),", kotlin)

    def test_info_css_keeps_its_own_rules_around_the_generated_region(self):
        info = self.root / "app/src/main/assets/info.css"
        before = info.read_text()
        self.recolour(lambda tokens: tokens["web"]["themed"]["bg"].update(dark="#010203"))
        self.assertEqual(self.run_tokens().returncode, 0)
        after = info.read_text()
        self.assertIn("--bg:#010203;", after)
        start, end = "/* tokens:begin", "/* tokens:end */"
        self.assertEqual(before[:before.index(start)], after[:after.index(start)])
        self.assertEqual(before[before.index(end):], after[after.index(end):])
        self.assertEqual(after[after.index(start):after.index(end)].count("--bg:"), 3)

    def test_a_native_colour_that_is_not_hex_is_refused(self):
        self.recolour(lambda tokens: tokens["native"]["notice"]["fields"]["accent"].update(dark="rgb(1,2,3)"))
        result = self.run_tokens()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("native.notice.accent.dark", result.stderr)


if __name__ == "__main__":
    unittest.main()
