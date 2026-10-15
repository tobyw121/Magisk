# Docker build on GitHub Actions

The `Magisk Build` workflow builds release and debug APKs inside an
`eclipse-temurin:25-jdk-noble` Docker job on an Ubuntu x64 runner. It runs on
relevant pushes to `master`, pull requests targeting `master`, and manually
through **Actions → Magisk Build → Run workflow**.

The workflow installs Linux build dependencies, checks out all submodules, and
runs `setup.sh` from the repository root. The script installs Android SDK 37.1,
build-tools 37.0.0, and the ONDK version pinned in `scripts/env.py`. The Android
command-line tools download is checked against a pinned SHA-256 checksum.
Android SDK/ONDK and Gradle/Cargo downloads are cached between runs.

`./build.py -vr all` and `./build.py -v all` use the default ABI set: ARM64,
ARM32, x86_64, and x86. This includes both ABIs needed for `zygote64_32` devices.
Builds for one workflow run execute sequentially. A new run cancels an older
run for the same Git ref.

Download the APKs from the successful run's **Artifacts** section, in the
artifact named after the commit SHA. It contains `app-release.apk`,
`app-debug.apk`, `stub-release.apk`, `stub-debug.apk`, and `test.apk`.
APKs are retained for 14 days. Mapping/debug outputs are uploaded separately;
failed builds upload any available build logs. The test APK is compiled;
building it does not execute its device tests. The existing host and emulator
jobs remain in the workflow.

No signing secrets, registry credentials, or release publishing permissions are
required. Without a custom `config.prop` signing configuration, builds use the
repository's development signing behavior; they are not official Magisk
releases and cannot be assumed to update an official signed installation.

The workflow becomes active after these files are committed and pushed to
GitHub with Actions enabled. No container registry image or GitHub Release is
published by this workflow.
