"""Run the recovery transcript wrapper against isolated fake device paths."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

REPO = Path(__file__).resolve().parents[2]
LOGGER = REPO / 'scripts/recovery_log.sh'
APP = REPO / 'scripts/app_functions.sh'
UTIL = REPO / 'scripts/util_functions.sh'


class RecoveryLoggingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='magisk-log-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for directory in ['work', 'tmp', 'data/adb/magisk', 'cache', 'proc']:
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        # Dispatch the same applet calls to host tools; production uses BusyBox.
        self.box = self.root / 'work/busybox'
        self.box.write_text('#!/bin/sh\nif [ "$1" = sh ]; then shift; exec /bin/bash "$@"; fi\nexec "$@"\n')
        self.box.chmod(0o755)
        script = LOGGER.read_text()
        for path in ['/proc/mounts', '/data/', '/cache/', '/tmp/magisk-recovery.log']:
            script = script.replace(path, str(self.root) + path)
        self.logger = self.root / 'logger.sh'
        self.logger.write_text(script)
        self.log = self.root / 'work/recovery.log'
        self.log.write_text('=== Bootstrap fixture ===\n')

    def run_logger(self, status=0, persistent=True, corrupt=False):
        (self.root / 'proc/mounts').write_text(
            '/dev/data /data ext4 rw 0 0\n/dev/cache /cache ext4 rw 0 0\n'
            if persistent else '')
        archive = self.root / 'Magisk.apk'
        if corrupt:
            archive.write_text('broken archive')
        else:
            with ZipFile(archive, 'w') as z:
                child = f'''#!/bin/sh
. {shlex.quote(str(UTIL))}
TMPDIR={shlex.quote(str(self.root / 'tmp/extracted'))}
BOOTMODE=false
OUTFD=1
INSTALL_STAGE=fixture-flash
DATA=false
DATA_DE=false
ui_print 'UI diagnostic message'
echo 'Native stdout message'
echo 'Native stderr message' >&2
rm -rf "$TMPDIR"
exit {status}
'''
                z.writestr('META-INF/com/google/android/updater-script', child)
                z.writestr('assets/fixture', 'fixture asset')
                z.writestr('lib/x86_64/libmagisk.so', 'fixture binary')
                z.writestr('lib/x86_64/libbusybox.so', 'excluded fixture')
        env = dict(os.environ, MAGISK_RECOVERY_LOG=str(self.log),
                   MAGISK_RECOVERY_BUSYBOX=str(self.box),
                   INSTALLER=str(self.root / 'tmp/extracted'))
        return subprocess.run(['/bin/bash', str(self.logger), '3', '2', str(archive)],
                              env=env, capture_output=True, text=True)

    def test_stdout_stderr_ui_and_footer_survive_installer_cleanup(self):
        result = self.run_logger()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        saved = (self.root / 'cache/magisk-recovery.log').read_text()
        for text in ['Bootstrap fixture', 'UI diagnostic message', 'Native stdout message',
                     'Native stderr message', 'Installer exit status=0',
                     'Saving recovery transcript:', 'updater-script']:
            self.assertIn(text, saved)
        self.assertEqual(saved, (self.root / 'data/adb/magisk/recovery.log').read_text())
        self.assertEqual(saved, (self.root / 'tmp/magisk-recovery.log').read_text())
        self.assertFalse((self.root / 'tmp/extracted').exists())

    def test_failure_exit_status_is_not_hidden_by_tee(self):
        result = self.run_logger(status=7)
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        self.assertIn('Installer exit status=7', (self.root / 'cache/magisk-recovery.log').read_text())

    def test_without_persistent_storage_log_stays_available_in_recovery(self):
        result = self.run_logger(persistent=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / 'cache/magisk-recovery.log').exists())
        self.assertFalse((self.root / 'data/adb/magisk/recovery.log').exists())
        saved = (self.root / 'tmp/magisk-recovery.log').read_text()
        self.assertIn('Native stderr message', saved)
        self.assertIn('not mounted on persistent storage', saved)

    def test_bad_archive_is_logged_and_fails_before_installation(self):
        result = self.run_logger(corrupt=True)
        self.assertNotEqual(result.returncode, 0)
        saved = (self.root / 'tmp/magisk-recovery.log').read_text()
        self.assertIn('Archive extraction failed', saved)
        self.assertIn('Installer exit status=1', saved)
        self.assertNotIn('Native stdout message', saved)

    def run_bootstrap(self, fail_mkdir=False):
        script = (REPO / 'scripts/update_binary.sh').read_text()
        script = script.replace('/tmp/magisk-recovery.log', str(self.root / 'tmp/magisk-recovery.log'))
        script = script.replace('TMPDIR=/dev/tmp', 'TMPDIR=' + shlex.quote(str(self.root / 'tmp/bootstrap')))
        script = script.replace('/tmp/magisk-recovery.XXXXXX', str(self.root / 'tmp/magisk-recovery.XXXXXX'))
        bootstrap = self.root / 'bootstrap.sh'
        bootstrap.write_text(script)
        env = dict(os.environ)
        if fail_mkdir:
            fakebin = self.root / 'fakebin'
            fakebin.mkdir()
            mkdir = fakebin / 'mkdir'
            mkdir.write_text('#!/bin/sh\necho "fixture mkdir failure" >&2\nexit 1\n')
            mkdir.chmod(0o755)
            env['PATH'] = str(fakebin) + os.pathsep + env['PATH']
        return subprocess.run(['/bin/bash', str(bootstrap), '3', '2', str(self.root / 'missing.apk')],
                              env=env, capture_output=True, text=True)

    def test_bootstrap_failure_before_busybox_is_retained(self):
        result = self.run_bootstrap()
        self.assertNotEqual(result.returncode, 0)
        saved = (self.root / 'tmp/magisk-recovery.log').read_text()
        self.assertIn('Recovery bootstrap', saved)
        self.assertIn('Trying BusyBox ABI=', saved)
        self.assertIn('missing.apk', saved)
        self.assertIn('Cannot run bundled BusyBox', result.stderr)

    def test_bootstrap_work_directory_error_has_a_failure_stage(self):
        result = self.run_bootstrap(fail_mkdir=True)
        self.assertNotEqual(result.returncode, 0)
        saved = (self.root / 'tmp/magisk-recovery.log').read_text()
        self.assertIn('fixture mkdir failure', saved)
        self.assertIn('Bootstrap failed: create work directory', saved)

    def read_app_log(self):
        script = APP.read_text()
        for path in ['/data/', '/cache/']:
            script = script.replace(path, str(self.root) + path)
        helper = self.root / 'app-functions.sh'
        helper.write_text(script)
        return subprocess.run(['/bin/sh', '-c', f'. {shlex.quote(str(helper))}\napp_recovery_logs'],
                              capture_output=True, text=True)

    def test_app_uses_newest_recovery_log(self):
        old = self.root / 'data/adb/magisk/recovery.log'
        new = self.root / 'cache/magisk-recovery.log'
        old.write_text('old installation')
        new.write_text('new installation failure')
        os.utime(old, (100, 100))
        os.utime(new, (200, 200))
        result = self.read_app_log()
        self.assertEqual(result.returncode, 0)
        self.assertIn('new installation failure', result.stdout)
        self.assertNotIn('old installation', result.stdout)

    def test_app_explains_when_no_transcript_can_be_read(self):
        result = self.read_app_log()
        self.assertEqual(result.returncode, 0)
        self.assertIn('Root access may be required', result.stdout)
        self.assertIn('adb pull /tmp/magisk-recovery.log', result.stdout)


if __name__ == '__main__':
    unittest.main()
