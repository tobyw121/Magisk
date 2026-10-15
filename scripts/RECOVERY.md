# Recovery installation and diagnostics

A manual recovery flash performs a clean Magisk installation when `/data/adb`
is accessible and writable. It prepares and verifies the new boot image before
removing existing Magisk binaries, modules (including pending updates), the
Magisk database and SQLite sidecars, startup scripts, saved installation flags,
legacy module images, and Magisk's known preinit directories. Other `/data/adb`
contents, application data, `/system/init` and stock-image backup directories
are preserved. Superuser permissions, DenyList and Zygisk settings are reset.
This reset does not run during in-app or addon.d/OTA upgrades.

Cleanup checks every target first and rejects live nested mounts or unexpected
symlinked parent directories. Recognized preinit partitions must be accessible
through recovery's fstab before a clean reset. Cleanup or image verification
failure stops installation before writing the boot partition.

If `/data/adb` cannot be accessed, the installer still patches, verifies and
flashes the boot image. It logs that old modules/settings were **not removed**.
Install files use `/data/magisk` or `/cache/data_adb/magisk` when possible. With
no writable persistent storage it flashes from recovery RAM and reports that
the Magisk environment may need repair after boot; successful flashing alone
does not prove that root is working. Old data is not silently wiped on a later
boot.

The recovery transcript captures archive extraction, UI messages, stdout and
stderr, selected device/mount/storage diagnostics, the installation stage and
the final exit status. Failures retain their original exit status through the
logging pipeline. No process environment or key material is dumped.

The complete transcript is available during recovery at:

```sh
adb pull /tmp/magisk-recovery.log
```

When storage is mounted and writable, copies are saved to
`/data/adb/magisk/recovery.log` (or `/data/magisk/recovery.log`) and
`/cache/magisk-recovery.log`. The Magisk log view and exported diagnostic report
include the newest readable copy under **Recovery Installer Log**, even when
the daemon is not active. Reading these protected files may require root.
If no persistent storage is writable, pull the transcript before rebooting:
recovery RAM does not survive a restart.

Host regression tests use temporary fixture filesystems, not device partitions:

```sh
scripts/env.py python3 -B -m unittest discover -s scripts/tests -v
```
