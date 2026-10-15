# Magisk APK build on GitHub Actions

The `Magisk Build` workflow creates one universal release APK named
`Magisk.apk` in an `eclipse-temurin:25-jdk-noble` Docker container on an
Ubuntu x64 runner. It runs on relevant pushes to `master`, pull requests
targeting `master`, and manually through **Actions → Magisk Build → Run workflow**.

The workflow checks out all submodules and installs host build dependencies,
Android SDK 37.1, build-tools 37.0.0 and the ONDK version from `scripts/env.py`.
The command-line tools download is verified with a pinned SHA-256 checksum.
SDK/ONDK and Gradle/Cargo dependency downloads are cached between runs.

The build runs `./build.py -vr native` and `./build.py -vr app`. ARM64,
ARM32, x86_64 and x86 are packaged together in the universal APK, including
both ABIs needed by `zygote64_32` devices. The stub is built internally and
embedded in the app; no separate stub, debug or test APK is uploaded.
The emulator jobs that depended on separate debug/test APKs are not part
of this APK-only workflow. Building the APK does not run device tests.

Download **Magisk-APK** from the successful run under **Artifacts**. GitHub
wraps artifact downloads in a ZIP archive; extract `Magisk.apk` from it.
The file itself is an installable APK, not an installer ZIP that needs renaming.
The APK is retained for 14 days. A failed build uploads available build logs.
New runs cancel older runs for the same Git ref.

No signing secrets, registry credentials or publishing permissions are required.
Without custom signing settings in `config.prop`, the APK uses development
signing and cannot be assumed to update an official signed Magisk installation.
No GitHub Release or container registry image is published.

For the same output locally, run the two build commands above and copy
`out/app-release.apk` to `out/Magisk.apk`. The existing `build.py all` command
still supports development builds, including its test APK.
