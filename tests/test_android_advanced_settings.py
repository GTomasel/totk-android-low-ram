"""Host tests for the advanced launcher model and Java-to-native settings contract."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'games/totk'
SETUP = ROOT / 'android/java/com/aure/totk/setup'


class AndroidAdvancedSettingsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        javac, java = shutil.which('javac'), shutil.which('java')
        if not javac or not java:
            raise unittest.SkipTest('javac/java not found')
        workspace = tempfile.TemporaryDirectory(prefix='totk-advanced-settings-')
        cls.addClassCleanup(workspace.cleanup)
        cls.classes = Path(workspace.name)
        compiled = subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(cls.classes),
                                   str(SETUP / 'Settings.java'), str(SETUP / 'AdvancedSettings.java'),
                                   str(ROOT / 'tests/settings/AdvancedSettingsProbe.java')],
                                  capture_output=True, text=True)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)
        cls.java = java

    def run_probe(self, *args):
        result = subprocess.run([self.java, '-cp', str(self.classes), 'com.aure.totk.setup.AdvancedSettingsProbe', *args],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def test_validate_combine_replace_lock_and_reset_settings(self):
        self.run_probe('verify', str(self.classes))

    def test_every_menu_value_matches_the_native_allowlist(self):
        source = (ROOT / 'runtime/frontend_android.c').read_text()
        begin = source.index('advanced_settings[] = {')
        table = source[begin:source.index('};', begin)]
        allowed = {key: set(items.split(',')) for key, items in re.findall(r'\{"(\w+)", "([^"]+)", [01]\}', table)}
        choices = {}
        for line in self.run_probe('choices').splitlines():
            key, value = line.split('=', 1)
            choices.setdefault(key, set()).add(value)
        # The normal graphics profile uses auto for the 40 fps mode.
        choices['TOTK_PRESENT_SLOTS'].add('auto')
        self.assertEqual(choices, allowed)
