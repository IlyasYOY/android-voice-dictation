#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"

errors=0
ok() { printf 'OK    %s\n' "$*"; }
missing() { printf 'ERROR %s\n' "$*" >&2; errors=$((errors + 1)); }

printf 'Project: %s\nSDK:     %s\n' "$ANDROID_PROJECT_ROOT" "$ANDROID_HOME"
if command -v python3 >/dev/null 2>&1; then
    ok 'Python 3 (CI signing policy tests)'
else
    missing 'Python 3 is required for CI signing policy tests.'
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$JAVA_HOME/bin/java"
    javac_bin="$JAVA_HOME/bin/javac"
else
    java_bin="$(command -v java || true)"
    javac_bin="$(command -v javac || true)"
fi
if [[ -x "$java_bin" && -x "$javac_bin" ]]; then
    java_version="$("$java_bin" -version 2>&1)"
    java_major="$(printf '%s\n' "$java_version" | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
    if [[ "$java_major" == 17 || "$java_major" == 21 ]]; then
        ok "JDK $java_major ($java_bin)"
    else
        missing "Expected JDK 17 or 21; detected ${java_major:-unknown}. Check JAVA_HOME."
    fi
else
    missing 'A full JDK 17 or 21 is required; java and javac must both exist.'
fi

for relative in \
    "platforms/android-$ANDROID_COMPILE_API/android.jar" \
    "build-tools/$ANDROID_BUILD_TOOLS/aapt2" \
    "build-tools/$ANDROID_BUILD_TOOLS/apksigner" \
    "platform-tools/adb"; do
    if [[ -f "$ANDROID_HOME/$relative" ]]; then
        ok "$relative"
    else
        missing "Missing SDK file: $relative. Install the documented SDK packages."
    fi
done

for relative in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties; do
    if [[ -f "$ANDROID_PROJECT_ROOT/$relative" ]]; then
        ok "$relative"
    else
        missing "Missing $relative. Use a complete repository checkout."
    fi
done
if [[ -f "$ANDROID_PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" ]] &&
   ! grep -Eq '^distributionSha256Sum=[[:xdigit:]]{64}$' "$ANDROID_PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties"; then
    missing 'Gradle distribution checksum is absent or malformed.'
fi

if [[ -e /dev/kvm && -r /dev/kvm && -w /dev/kvm ]]; then
    ok 'KVM is accessible (emulator packages and a device are still needed).'
else
    printf 'INFO  KVM is unavailable. Build, lint, and JVM unit tests do not need an emulator.\n'
fi
if (( errors > 0 )); then
    printf '\nEnvironment check failed with %d issue(s). See docs/cloud-environment.md.\n' "$errors" >&2
    exit 1
fi
printf '\nEnvironment prerequisites look good. This did not build or test the app.\n'
