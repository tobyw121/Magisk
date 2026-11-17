# App hiding and root isolation in this fork

This fork adds an experimental **Hide the Magisk app** action under
Settings → Magisk. It repackages the complete APK with a random label,
package name, default Android process name, and a freshly generated signing
certificate. Fully qualified component classes, app contents, and its icon
remain identifiable; this is not universal root detector evasion.

The root daemon accepts the hidden manager only when its installed APK
certificate matches the root-owned enrollment at
`/data/adb/magisk_manager_identity` (regular file, mode 0600). The original
manager's certificate validation remains unchanged. Superuser requests use
the verified manager's provider, so root management continues after hiding.
No blanket root grants are added.

Install this fork into the boot image and reboot before hiding the app.
The action checks support in the running daemon to reject app-only updates
on an incompatible daemon. Only the owner user can migrate the app. The
original APK is backed up at `/data/adb/magisk_manager_original.apk`; the
original installed package is removed only after the hidden copy acquires
root. App-local preferences are not transferred; daemon settings and root
policies stay in the Magisk database. Restore the original app using the
same Settings action before any app or boot-image update. If migration
fails before the hidden copy starts, the original installation is retained.

For selected applications, enable Zygisk, reboot, configure the **DenyList**,
and enable its enforcement. DenyList removes Magisk modifications from the
selected processes; it is separate from hiding the manager APK. This fork
does not bring back the historical MagiskHide property spoofing engine,
forge hardware attestation, conceal an unlocked bootloader globally, or
guarantee SafetyNet / Play Integrity success. Device testing is required,
especially for Android 16 and third-party Zygisk providers.

DenyList edits are serialized and read back from the daemon. A failed write
is not shown as a successful selection; partial batches display the entries
that actually remain configured. Selecting a partially selected app fills
in its missing processes. Isolated services use a canonical app prefix,
so a legacy entry for one service is not mistaken for coverage of all its
isolated services. Removing an app also removes stale normal process
entries and matching legacy isolated entries. Broad isolated prefixes that
also affect other apps are retained and reported as an unsuccessful
removal, rather than silently changing those other apps' configuration.
These checks verify stored configuration, not hardware attestation or the
runtime isolation of a particular process. Metadata stays cached while
reopening the screen refreshes the selection from the daemon.

The GitHub workflow builds release and debug APKs on **Ubuntu 24.04**.
Every job, including emulator tests and the auxiliary build check, uses that
runner. APKs are uploaded in the workflow's build artifact. The standard
build uses a development signing key unless a release key is configured;
it is not an official upstream-signed release.

## Default selection of applications

On first root-enabled startup, installed packages with application UIDs
(including preinstalled apps) are added to the DenyList with their declared
processes and isolated-service prefixes. The manager package, packages
sharing its UID, and core system UIDs are excluded to preserve management
and essential system services. Successful initialization is recorded per
package in the root-owned, mode-0600 file
`/data/adb/magisk_denylist_defaults`, which survives app hiding/restoration.
Manual app exclusions persist; a previously initialized package is not
seeded again. New packages are scanned when the manager resumes, not by an
always-running installation monitor. Failed packages are retried later.
Enforcement is enabled once; later manual disabling is respected. The
existing Zygisk provider/configuration and all su policies are preserved.
Zygisk must actually be running for process isolation to take effect.
Selecting an app does not revoke its existing root permission and does not
provide hardware attestation spoofing or guaranteed root invisibility.
