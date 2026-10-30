#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"
cd "$ANDROID_PROJECT_ROOT"
adb get-state
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest
for phase in save restore forgotten; do
  adb shell am force-stop io.github.ilyasyoy.voicedictation
  result_file="/tmp/dictation-key-${phase}.log"
  timeout 120 adb shell am instrument -w \
    -e class io.github.ilyasyoy.voicedictation.KeyPersistenceRestartTest \
    -e keyPhase "$phase" \
    io.github.ilyasyoy.voicedictation.test/androidx.test.runner.AndroidJUnitRunner > "$result_file"
  cat "$result_file"
  rg --quiet 'OK \(1 test\)' "$result_file"
done
