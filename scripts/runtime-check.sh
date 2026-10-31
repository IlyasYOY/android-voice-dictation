#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"
cd "$ANDROID_PROJECT_ROOT"
adb get-state
# Separate test device only: permission-denial test needs a known denied initial state.
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest
adb shell pm revoke io.github.ilyasyoy.voicedictation android.permission.RECORD_AUDIO
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyasyoy.voicedictation.DeniedPermissionTest,io.github.ilyasyoy.voicedictation.DictationScreenTest,io.github.ilyasyoy.voicedictation.LiveScreenTest,io.github.ilyasyoy.voicedictation.KeyPersistenceRestartTest,io.github.ilyasyoy.voicedictation.ApiKeyStoreTest,io.github.ilyasyoy.voicedictation.ScreenshotPolicyTest,io.github.ilyasyoy.voicedictation.SoftCurrentScreenTest
mkdir -p captures/evidence
adb pull /sdcard/Download/dictation-evidence/. captures/evidence/
