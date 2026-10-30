#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"

fail() { printf 'Setup error: %s\n' "$*" >&2; exit 1; }

[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] ||
    fail 'This installer supports Linux x86_64 only. On another host, install the matching Android SDK manually and set ANDROID_HOME.'
[[ "${ANDROID_ACCEPT_LICENSES:-}" == yes ]] || fail \
    'Read https://developer.android.com/studio/terms first. Only if you agree, run ANDROID_ACCEPT_LICENSES=yes bash scripts/setup-android.sh. No SDK changes were made.'

for tool in curl unzip sha256sum mktemp; do
    command -v "$tool" >/dev/null 2>&1 || fail "Missing $tool. Use an environment image containing it; this script does not use sudo."
done
if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -x "$JAVA_HOME/bin/java" ]] || fail 'JAVA_HOME does not contain bin/java.'
    [[ -x "$JAVA_HOME/bin/javac" ]] || fail 'JAVA_HOME must point to a full JDK (bin/javac is missing).'
    java_bin="$JAVA_HOME/bin/java"
else
    java_bin="$(command -v java || true)"
    [[ -x "$java_bin" ]] || fail 'No java executable found. Install JDK 17 or 21 in the environment image or set JAVA_HOME.'
    command -v javac >/dev/null 2>&1 || fail 'No javac executable found. A full JDK 17 or 21 is required, not only a Java runtime.'
fi
java_version="$("$java_bin" -version 2>&1)"
java_major="$(printf '%s\n' "$java_version" | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
[[ "$java_major" == 17 || "$java_major" == 21 ]] || fail \
    "Use JDK 17 or 21 for this pinned build (detected Java ${java_major:-unknown})."

mkdir -p "$ANDROID_HOME/.scaffold-tools"
[[ -w "$ANDROID_HOME" ]] || fail "SDK is not writable: $ANDROID_HOME. Set ANDROID_HOME to a dedicated writable directory."
install_root="$(dirname -- "$ANDROID_SCAFFOLD_TOOLS")"
marker="$install_root/archive.sha256"
temp_dir="$(mktemp -d "$ANDROID_HOME/.scaffold-tools/.setup.XXXXXXXX")"
trap 'rm -rf -- "$temp_dir"' EXIT

if [[ -e "$install_root" ]]; then
    [[ -f "$marker" && "$(cat "$marker")" == "$ANDROID_COMMAND_TOOLS_SHA256" &&
       -x "$ANDROID_SCAFFOLD_TOOLS/bin/sdkmanager" ]] || fail \
        "An incomplete or unrecognized install exists at $install_root. Inspect it and choose a clean ANDROID_HOME; it has not been overwritten."
    printf 'Using pinned command-line tools: %s\n' "$ANDROID_SCAFFOLD_TOOLS"
else
    archive="$temp_dir/commandlinetools.zip"
    url="https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_TOOLS_BUILD}_latest.zip"
    curl --fail --show-error --location --proto '=https' --tlsv1.2 \
        --retry 3 --connect-timeout 30 --max-time 600 --output "$archive" "$url"
    printf '%s  %s\n' "$ANDROID_COMMAND_TOOLS_SHA256" "$archive" | sha256sum --check --status ||
        fail 'Android command-line-tools checksum mismatch. Archive was not executed.'
    mkdir "$temp_dir/install"
    unzip -q "$archive" -d "$temp_dir/install"
    [[ -x "$temp_dir/install/cmdline-tools/bin/sdkmanager" ]] || fail 'Archive has no sdkmanager executable.'
    printf '%s\n' "$ANDROID_COMMAND_TOOLS_SHA256" > "$temp_dir/install/archive.sha256"
    # Do not replace a concurrent installer or an existing SDK installation.
    [[ ! -e "$install_root" ]] || fail "Another installer created $install_root; rerun setup after it finishes."
    mv -T -n -- "$temp_dir/install" "$install_root"
    [[ ! -d "$temp_dir/install" ]] || fail "Another installer created $install_root; nothing was overwritten."
fi

sdkmanager="$ANDROID_SCAFFOLD_TOOLS/bin/sdkmanager"
"$sdkmanager" --sdk_root="$ANDROID_HOME" --version

# Only request these project packages. Do NOT use sdkmanager --licenses: that
# command offers licenses for unrelated packages too. Consent is limited to the
# licenses required by this explicit install, not every available SDK license.
# Capture the SDK manager's exit code, not yes(1)'s expected SIGPIPE (141).
set +e
yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --channel=0 --install \
    "platform-tools" "platforms;android-$ANDROID_COMPILE_API" "build-tools;$ANDROID_BUILD_TOOLS"
install_status=${PIPESTATUS[1]}
set -e
[[ "$install_status" -eq 0 ]] || fail "sdkmanager package installation or required-license acceptance failed (exit $install_status)."

printf '\nSDK installed. In each new shell, source scripts/android-env.sh (or persist ANDROID_HOME).\n'
bash "$ANDROID_PROJECT_ROOT/scripts/doctor.sh"
