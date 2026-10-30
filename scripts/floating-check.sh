#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"
cd "$ANDROID_PROJECT_ROOT"
: "${ANDROID_SERIAL:?Select a dedicated test emulator with ANDROID_SERIAL}"
# This installs a separate harmless fixture, uses OS permission dialogs, and enables
# the optional service via its explicit disclosure and system Accessibility UI.
# It must never target an everyday phone with real fields/accounts.
./gradlew :app:assembleDebug :fixture:assembleDebug :fixture:assembleDebugAndroidTest
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb install --no-streaming -r fixture/build/outputs/apk/debug/fixture-debug.apk
adb install --no-streaming -r fixture/build/outputs/apk/androidTest/debug/fixture-debug-androidTest.apk
mkdir -p fixture/build/crossapp-results
set +e
timeout 900 adb shell am instrument -w \
    -e class io.github.ilyasyoy.voicedictation.fixture.CrossAppTest \
    io.github.ilyasyoy.voicedictation.fixture.test/androidx.test.runner.AndroidJUnitRunner \
    | tee fixture/build/crossapp-results/instrumentation.txt
run_status=${PIPESTATUS[0]}
set -e
adb pull /sdcard/Download/crossapp-evidence fixture/build/crossapp-results/ || true
if [[ $run_status != 0 ]] || ! rg -q '^OK \(' fixture/build/crossapp-results/instrumentation.txt; then
    echo 'Cross-app instrumentation failed or did not report a complete result.' >&2
    exit 1
fi
