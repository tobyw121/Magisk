#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root inside a Linux x64 container with JDK 25,
# Python 3, Git, a host C/C++ toolchain, make, curl, unzip and xz installed.
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"

tools_version=13114758
tools_sha256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$sdkmanager" ]]; then
    download_dir=$(mktemp -d)
    trap 'rm -rf "$download_dir"' EXIT
    curl --fail --location --retry 3 \
        "https://dl.google.com/android/repository/commandlinetools-linux-${tools_version}_latest.zip" \
        --output "$download_dir/tools.zip"
    echo "$tools_sha256  $download_dir/tools.zip" | sha256sum --check -
    unzip -q "$download_dir/tools.zip" -d "$download_dir"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    mv "$download_dir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

# yes may receive SIGPIPE after sdkmanager has consumed its input. Preserve
# sdkmanager's status while avoiding a false failure from pipefail.
set +o pipefail
yes | "$sdkmanager" --licenses
set -o pipefail
"$sdkmanager" "platforms;android-37.1" "build-tools;37.0.0" "platform-tools"

required_ondk=$(python3 -B -c 'from scripts.env import ondk_version; print(ondk_version)')
installed_ondk=$(cat "$ANDROID_HOME/ndk/magisk/ONDK_VERSION" 2>/dev/null || true)
if [[ "$installed_ondk" != "$required_ondk" ]]; then
    ./build.py -v ndk
fi

# Use the repository wrapper to verify both toolchains before the build.
./scripts/env.py javac -version
./scripts/env.py ./app/gradlew --version
