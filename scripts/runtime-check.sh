#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"
cd "$ANDROID_PROJECT_ROOT"
adb get-state
# A flowing native AndroidView intentionally never becomes Compose-idle. Exercise reduced motion
# for synchronized Compose tests; MotionSmokeTest separately enables/restores animation for real motion.
previous_motion_scale="$(adb shell settings get global animator_duration_scale | tr -d '\r')"
restore_motion() {
  if [[ "$previous_motion_scale" == null ]]; then adb shell settings delete global animator_duration_scale >/dev/null
  else adb shell settings put global animator_duration_scale "$previous_motion_scale"; fi
}
trap restore_motion EXIT
adb shell settings put global animator_duration_scale 0
# Separate test device only: permission-denial test needs a known denied initial state.
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest
adb shell pm revoke io.github.ilyasyoy.voicedictation android.permission.RECORD_AUDIO
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyasyoy.voicedictation.DeniedPermissionTest,io.github.ilyasyoy.voicedictation.DictationScreenTest,io.github.ilyasyoy.voicedictation.LiveScreenTest,io.github.ilyasyoy.voicedictation.KeyPersistenceRestartTest,io.github.ilyasyoy.voicedictation.ApiKeyStoreTest,io.github.ilyasyoy.voicedictation.ScreenshotPolicyTest,io.github.ilyasyoy.voicedictation.SoftCurrentScreenTest,io.github.ilyasyoy.voicedictation.SettingsStorageFailureTest,io.github.ilyasyoy.voicedictation.SoftCurrentAcceptanceTest,io.github.ilyasyoy.voicedictation.MotionSmokeTest
mkdir -p captures/evidence
adb pull /sdcard/Download/dictation-evidence/. captures/evidence/
