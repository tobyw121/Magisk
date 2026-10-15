"""Read-back tests and real magiskboot round trips; no device partitions touched.

Run from the repository root after building native x86_64 binaries:
    scripts/env.py python3 -m unittest discover -s scripts/tests -v
"""
import gzip
import lzma
import os
from pathlib import Path
import shlex
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
UTIL = ROOT / 'scripts/util_functions.sh'
APP = ROOT / 'scripts/app_functions.sh'
TOOL = Path(os.environ.get('MAGISKBOOT_TEST_BINARY', ROOT / 'native/out/x86_64/magiskboot'))


class InstallVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='magisk-install-test-')
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.image = self.work / 'image with spaces.img'
        self.target = self.work / 'target.img'
        self.image.write_bytes(b'test-image\0' * 1024)

    def shell(self, command):
        return subprocess.run(['/bin/sh', '-c',
            f'. {shlex.quote(str(UTIL))}\nBOOTMODE=true\n{command}'],
            cwd=self.work, capture_output=True, text=True)

    def flash(self, prefix=''):
        return self.shell(prefix + '\nflash_image ' + shlex.quote(str(self.image)) +
                          ' ' + shlex.quote(str(self.target)))

    def test_regular_file_readback(self):
        result = self.flash()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.image.read_bytes(), self.target.read_bytes())
        self.assertIn('verified by read-back', result.stdout)

    def test_corrupted_readback_is_a_failure(self):
        self.assertEqual(self.flash('head() { printf corrupt; }').returncode, 3)

    def test_empty_source_does_not_overwrite_target(self):
        self.image.write_bytes(b'')
        self.target.write_bytes(b'existing-boot-image')
        self.assertEqual(self.flash().returncode, 3)
        self.assertEqual(self.target.read_bytes(), b'existing-boot-image')

    def test_failed_write_is_not_reported_as_success(self):
        self.target.mkdir()
        self.assertEqual(self.flash().returncode, 3)

    def test_compressed_restore_still_works_without_verification_claim(self):
        data = self.image.read_bytes()
        self.image = self.work / 'restore.gz'
        self.image.write_bytes(gzip.compress(data))
        result = self.flash()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.target.read_bytes(), data)
        self.assertNotIn('verified by read-back', result.stdout)

    def test_direct_install_preserves_image_when_verification_fails(self):
        patched = self.work / 'new-boot.img'
        patched.write_bytes(b'patched-image')
        result = self.shell(f'. {shlex.quote(str(APP))}\n'
            'flash_image() { return 3; }\n'
            'fix_env() { echo unexpected-fix; }\n'
            'run_migrations() { echo unexpected-migration; }\n'
            f'direct_install {shlex.quote(str(self.work))} unused')
        self.assertEqual(result.returncode, 3)
        self.assertNotIn('unexpected-', result.stdout)
        self.assertIn('read-back verification failed', result.stdout)
        self.assertEqual(patched.read_bytes(), b'patched-image')


class RuntimeProbeTest(unittest.TestCase):
    def probe(self, body):
        with tempfile.TemporaryDirectory(prefix='magisk-probe-test-') as directory:
            work = Path(directory)
            binary = work / 'magisk'
            binary.write_text('#!/bin/sh\n[ "$#" -eq 1 ] && [ "$1" = -v ] || exit 99\n' + body)
            binary.chmod(0o755)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['/bin/sh', '-c',
                f'. {shlex.quote(str(APP))}\napp_diagnostics'],
                env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return dict(line.split('=', 1) for line in result.stdout.splitlines())

    def test_existing_daemon_version(self):
        values = self.probe('echo 31.0:MAGISK:R\n')
        self.assertEqual(values['BINARY_FOUND'], 'true')
        self.assertEqual(values['DAEMON_CODE'], '0')
        self.assertEqual(values['DAEMON_VERSION'], '31.0:MAGISK:R')
        self.assertNotIn('DAEMON_ERROR', values)

    def test_failed_probe_is_not_a_version(self):
        values = self.probe('echo "daemon unavailable" >&2\nexit 1\n')
        self.assertEqual(values['DAEMON_CODE'], '1')
        self.assertEqual(values['DAEMON_ERROR'], 'daemon unavailable')
        self.assertNotIn('DAEMON_VERSION', values)


@unittest.skipUnless(TOOL.is_file(), 'Build native x86_64 magiskboot first')
class PatchedImageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='magisk-ramdisk-test-')
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        shutil.copyfile(TOOL, self.work / 'magiskboot')
        (self.work / 'magiskboot').chmod(0o755)
        for name in ['magiskinit', 'magisk', 'stub.apk', 'init-ld']:
            (self.work / name).write_bytes(('fixture-' + name).encode())

    def tool(self, *args):
        result = subprocess.run([str(self.work / 'magiskboot'), *map(str, args)],
                                cwd=self.work, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))

    def image(self, missing=None, corrupt=None):
        commands = ['add 0750 init magiskinit']
        (self.work / 'config').write_text('KEEPVERITY=true\n')
        if missing != 'marker':
            commands.append('add 0000 .backup/.magisk config')
        for name in ['magisk', 'stub', 'init-ld']:
            if missing == name:
                continue
            data = (self.work / ('stub.apk' if name == 'stub' else name)).read_bytes()
            (self.work / (name + '.xz')).write_bytes(
                b'corrupted' if corrupt == name else lzma.compress(data))
            commands.append(f'add 0644 overlay.d/sbin/{name}.xz {name}.xz')
        archive = self.work / 'ramdisk.source'
        self.tool('cpio', archive, *commands)
        ramdisk = archive.read_bytes()
        # Android boot header v0, page-aligned fake kernel and real CPIO ramdisk.
        header = struct.pack('<8s10I', b'ANDROID!', 4, 0, len(ramdisk), 0,
                             0, 0, 0, 2048, 0, 0)
        (self.work / 'new-boot.img').write_bytes(
            header.ljust(2048, b'\0') + b'KERN'.ljust(2048, b'\0') +
            ramdisk + b'\0' * (-len(ramdisk) % 2048))

    def verify(self):
        return subprocess.run(['/bin/sh', '-c',
            f'. {shlex.quote(str(UTIL))}\nBOOTMODE=true\n'
            'verify_magisk_image new-boot.img "$PWD/magiskinit"'],
            cwd=self.work, capture_output=True, text=True)

    def test_valid_ramdisk_and_payloads_roundtrip(self):
        self.image()
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(list(self.work.glob('.verify-boot.*')))

    def test_missing_payload_is_rejected(self):
        self.image(missing='init-ld')
        self.assertNotEqual(self.verify().returncode, 0)

    def test_corrupted_compressed_payload_is_rejected(self):
        self.image(corrupt='magisk')
        self.assertNotEqual(self.verify().returncode, 0)

    def test_stock_ramdisk_is_rejected(self):
        self.image(missing='marker')
        self.assertNotEqual(self.verify().returncode, 0)

    def test_changed_init_is_rejected_and_scratch_is_removed(self):
        self.image()
        (self.work / 'magiskinit').write_bytes(b'wrong-init')
        self.assertNotEqual(self.verify().returncode, 0)
        self.assertFalse(list(self.work.glob('.verify-boot.*')))


if __name__ == '__main__':
    unittest.main()
