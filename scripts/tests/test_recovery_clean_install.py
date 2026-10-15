"""Recovery cleanup tests in a temporary filesystem; no device writes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
UTIL = REPO / 'scripts/util_functions.sh'
FLASH = REPO / 'scripts/flash_script.sh'


class RecoveryCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='magisk-clean-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.put('proc/mounts', '')
        self.put('data/adb/magisk/old', 'old binary')
        self.put('data/adb/modules/hosts/system/etc/hosts', 'old module')
        self.put('data/adb/modules_update/test/update', 'pending module')
        self.put('data/adb/magisk.db', 'old permissions')
        self.put('data/adb/magisk.db-wal', 'old settings')
        self.put('data/adb/post-fs-data.d/old.sh', 'old startup')
        self.put('data/adb/service.d/old.sh', 'old service')
        self.put('data/.magisk', 'old flags')
        self.put('metadata/magisk/sepolicy.rule', 'old policy')
        self.put('data/adb/unrelated/keep', 'other tool')
        self.put('data/magisk_backup_original/boot.img.gz', 'stock backup')
        self.put('system/init', 'system init')
        self.put('data/user/0/com.topjohnwu.magisk/keep', 'app data')

    def put(self, name, content='fixture'):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def shell(self, command, prefix=''):
        return subprocess.run(['/bin/sh', '-c',
            f'. {shlex.quote(str(UTIL))}\n'
            'ui_print() { echo "$1"; }\nBOOTMODE=false\nDATA_DE=true\n' +
            prefix + '\n' + command], capture_output=True, text=True)

    def clean(self, prefix=''):
        return self.shell('clean_recovery_magisk ' + shlex.quote(str(self.root)), prefix)

    def test_clean_removes_magisk_state_but_preserves_other_data(self):
        result = self.clean()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for name in ['data/adb/magisk', 'data/adb/modules', 'data/adb/modules_update',
                     'data/adb/magisk.db', 'data/adb/magisk.db-wal',
                     'data/adb/post-fs-data.d', 'data/adb/service.d',
                     'data/.magisk', 'metadata/magisk']:
            self.assertFalse((self.root / name).exists(), name)
        for name in ['data/adb/unrelated/keep', 'data/magisk_backup_original/boot.img.gz',
                     'system/init', 'data/user/0/com.topjohnwu.magisk/keep']:
            self.assertTrue((self.root / name).is_file(), name)

    def test_booted_android_cannot_use_recovery_cleanup(self):
        self.assertNotEqual(self.clean('BOOTMODE=true').returncode, 0)
        self.assertTrue((self.root / 'data/adb/magisk.db').exists())

    def test_encrypted_or_unwritable_data_aborts_before_cleanup(self):
        self.assertNotEqual(self.clean('DATA_DE=false').returncode, 0)
        self.assertTrue((self.root / 'data/adb/magisk/old').exists())

    def test_nested_mount_aborts_before_removing_any_files(self):
        path = self.root / 'data/adb/modules/hosts/system'
        self.put('proc/mounts', f'tmpfs {path} tmpfs rw 0 0\n')
        result = self.clean()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Unmount before', result.stdout)
        self.assertTrue((self.root / 'data/adb/magisk/old').exists())
        self.assertTrue((self.root / 'data/adb/modules/hosts/system/etc/hosts').exists())

    def test_symlinked_parent_is_rejected_before_any_removal(self):
        (self.root / 'metadata').rename(self.root / 'metadata-real')
        (self.root / 'metadata').symlink_to(self.root / 'metadata-real', target_is_directory=True)
        self.assertNotEqual(self.clean().returncode, 0)
        self.assertTrue((self.root / 'data/adb/magisk/old').exists())
        self.assertTrue((self.root / 'metadata-real/magisk/sepolicy.rule').exists())

    def test_leaf_symlink_is_removed_without_following_it(self):
        outside = self.put('outside/keep', 'unrelated')
        (self.root / 'data/adb/magisk.db').unlink()
        (self.root / 'data/adb/magisk.db').symlink_to(outside)
        self.assertEqual(self.clean().returncode, 0)
        self.assertEqual(outside.read_text(), 'unrelated')
        self.assertFalse((self.root / 'data/adb/magisk.db').is_symlink())

    def test_known_persist_alias_is_cleaned_through_vendor_path(self):
        self.put('mnt/vendor/persist/magisk/sepolicy.rule', 'old policy')
        self.put('mnt/vendor/persist/calibration', 'device calibration')
        (self.root / 'persist').symlink_to(self.root / 'mnt/vendor/persist', target_is_directory=True)
        self.assertEqual(self.clean().returncode, 0)
        self.assertFalse((self.root / 'mnt/vendor/persist/magisk').exists())
        self.assertEqual((self.root / 'persist/calibration').read_text(), 'device calibration')

    def test_failed_removal_returns_failure(self):
        result = self.clean('rm() { return 1; }')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('Old Magisk data removed', result.stdout)

    def test_clean_flags_do_not_read_saved_configuration(self):
        result = self.shell('get_flags clean\necho "$KEEPVERITY:$KEEPFORCEENCRYPT:$RECOVERYMODE"',
            'DATA=true\nSYSTEM_AS_ROOT=true\n'
            'getprop() { echo encrypted; }\nfind_block() { :; }\n'
            'getvar() { echo unexpected-old-config; }')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('unexpected-old-config', result.stdout)
        self.assertIn('true:true:false', result.stdout)

    def test_missing_preinit_mount_stops_the_clean_install(self):
        result = self.shell('mount_recovery_magisk_storage',
            'is_mounted() { return 1; }\nfind_block() { echo /dev/fixture; }\n'
            'mount() { return 1; }')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Mount /metadata', result.stdout)
        self.assertTrue((self.root / 'data/adb/modules').exists())

    def test_mounted_vendor_persist_does_not_mount_alias_again(self):
        result = self.shell('mount_recovery_magisk_storage',
            'is_mounted() { [ "$1" = /metadata ] || [ "$1" = /mnt/vendor/persist ]; }\n'
            'find_block() { echo /dev/fixture; }\n'
            'mount() { echo unexpected-mount; return 1; }')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('unexpected-mount', result.stdout)

    def run_installer(self, fail_prepare=False, data_ready=True, fail_cleanup=False, cache_ready=True):
        installer = self.root / 'installer'
        common = installer / 'assets'
        common.mkdir(parents=True)
        self.put('installer/lib/arm64-v8a/libmagisk.so', 'fresh binary')
        self.put('installer/lib/armeabi-v7a/libmagisk.so', 'fresh 32-bit binary')
        busybox = self.put('busybox', 'busybox fixture')
        target = self.root / 'data/adb/magisk'
        boot = self.put('boot.img', 'original boot')
        temp = self.root / 'tmp'
        temp.mkdir()
        script = UTIL.read_text().replace('check_recovery_magisk_cleanup', 'real_check_cleanup')
        script = script.replace('clean_recovery_magisk()', 'real_clean_cleanup()')
        script += '\n' + f'''
BOOTMODE=false
TMPDIR={shlex.quote(str(temp))}
MAGISKBIN={shlex.quote(str(target))}
MAGISK_VER=test
MAGISK_VER_CODE=31000
ui_print() {{ echo "$1"; }}
setup_flashable() {{ :; }}
is_mounted() {{ return 0; }}
mount_partitions() {{ :; }}
mount_recovery_magisk_storage() {{ :; }}
recovery_has_persistent_cache() {{ {'true' if cache_ready else 'false'}; }}
check_data() {{
  DATA={'true' if data_ready else 'false'}
  DATA_DE={'true' if data_ready else 'false'}
  {'true' if data_ready else 'MAGISKBIN=' + shlex.quote(str(self.root / 'cache/data_adb/magisk'))}
}}
recovery_debug_state() {{ echo '[DEBUG] fixture state'; }}
get_flags() {{ [ "$1" = clean ] || exit 91; }}
find_boot_image() {{ BOOTIMAGE={shlex.quote(str(boot))}; }}
api_level_arch_detect() {{ API=36; ABI=arm64-v8a; ABI32=armeabi-v7a; }}
remove_system_su() {{ :; }}
recovery_cleanup() {{ :; }}
check_recovery_magisk_cleanup() {{ real_check_cleanup {shlex.quote(str(self.root))}; }}
clean_recovery_magisk() {{ {'return 1' if fail_cleanup else 'real_clean_cleanup ' + shlex.quote(str(self.root))}; }}
prepare_magisk_image() {{
  [ -f "$MAGISKBIN/magisk" ] || exit 92
  [ -f {shlex.quote(str(target / 'old'))} ] || exit 93
  {'abort "fixture: invalid image"' if fail_prepare else 'echo verified > "$MAGISKBIN/new-boot.img"'}
}}
flash_magisk_image() {{
  [ {'!' if data_ready else ''} -e {shlex.quote(str(self.root / 'data/adb/magisk.db'))} ] || exit 94
  [ {'!' if data_ready else ''} -e {shlex.quote(str(self.root / 'data/adb/modules'))} ] || exit 95
  [ ! -e "$MAGISKBIN/old" ] || exit 96
  [ "$(cat "$MAGISKBIN/magisk")" = 'fresh binary' ] || exit 97
  [ "$(cat "$MAGISKBIN/new-boot.img")" = verified ] || exit 98
  echo fresh-boot > "$BOOTIMAGE"
}}
'''
        (common / 'util_functions.sh').write_text(script)
        env = dict(os.environ, INSTALLER=str(installer), BBBIN=str(busybox))
        result = subprocess.run(['/bin/bash', str(FLASH), '3', '2', 'fixture.apk'],
                                env=env, cwd=self.root, capture_output=True, text=True)
        return result, boot

    def test_full_recovery_flow_cleans_then_installs_fresh_files(self):
        result, boot = self.run_installer()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(boot.read_text(), 'fresh-boot\n')
        self.assertFalse((self.root / 'data/adb/modules').exists())
        self.assertEqual((self.root / 'data/adb/magisk/magisk').read_text(), 'fresh binary')
        self.assertTrue((self.root / 'data/adb/unrelated/keep').is_file())

    def test_bad_image_preserves_old_modules_settings_and_boot(self):
        result, boot = self.run_installer(fail_prepare=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(boot.read_text(), 'original boot')
        self.assertTrue((self.root / 'data/adb/modules').exists())
        self.assertTrue((self.root / 'data/adb/magisk.db').exists())

    def test_unavailable_data_installs_via_cache_and_logs_skipped_cleanup(self):
        result, boot = self.run_installer(data_ready=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(boot.read_text(), 'fresh-boot\n')
        self.assertTrue((self.root / 'data/adb/magisk/old').exists())
        self.assertTrue((self.root / 'data/adb/magisk.db').exists())
        self.assertEqual((self.root / 'cache/data_adb/magisk/magisk').read_text(), 'fresh binary')
        self.assertIn('clean reset skipped', result.stdout)

    def test_no_data_or_cache_still_flashes_and_reports_ram_only_install(self):
        result, boot = self.run_installer(data_ready=False, cache_ready=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(boot.read_text(), 'fresh-boot\n')
        self.assertIn('flashing from recovery RAM', result.stdout)
        self.assertIn('root is not guaranteed', result.stdout)
        self.assertTrue((self.root / 'data/adb/magisk.db').exists())

    def test_cleanup_failure_prevents_boot_write(self):
        result, boot = self.run_installer(fail_cleanup=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(boot.read_text(), 'original boot')
        self.assertNotIn('- Done', result.stdout)


if __name__ == '__main__':
    unittest.main()
