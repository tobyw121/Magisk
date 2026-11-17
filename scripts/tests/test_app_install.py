"""Exercise APK installation staging without installing an Android package."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class InstallStagingTest(unittest.TestCase):
    def exercise(self, status):
        script = Path(__file__).resolve().parents[1] / "app_functions.sh"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "input.apk"
            apk.write_bytes(b"fixture APK")
            staging = root / "staging"
            staging.mkdir()
            env = dict(os.environ, FIXTURE_DIR=str(staging), PM_STATUS=str(status))
            result = subprocess.run(["bash", "-c", '''
                source "$1"
                mktemp() { command mktemp -d "$FIXTURE_DIR/install.XXXXXX"; }
                su() { if [ "$PM_STATUS" = 0 ]; then return 1; else return "$PM_STATUS"; fi; }
                pm() {
                    [ "$1" = install ] || return 99
                    [ "$(stat -c %a "${@: -1}")" = 644 ] || return 98
                    [ "$(stat -c %a "$(dirname "${@: -1}")")" = 755 ] || return 97
                    [ "$(cat "${@: -1}")" = "fixture APK" ] || return 96
                    return "$PM_STATUS"
                }
                appops() { return 0; }
                adb_pm_install "$2" fixture.pkg
            ''', "fixture", str(script), str(apk)], env=env, capture_output=True, text=True)
            self.assertEqual(status, result.returncode, result.stderr)
            self.assertEqual([], list(staging.iterdir()))
            self.assertEqual(b"fixture APK", apk.read_bytes())

    def test_success_cleans_private_staging(self):
        self.exercise(0)

    def test_failure_cleans_private_staging(self):
        self.exercise(7)


if __name__ == "__main__":
    unittest.main()
