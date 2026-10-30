#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/android-env.sh"
cd "$ANDROID_PROJECT_ROOT"
bash scripts/doctor.sh
# Test APK compilation is not the same as running UI/instrumentation tests.
bash ./gradlew --no-daemon \
    :app:assembleDebug \
    :app:lintDebug \
    :app:testDebugUnitTest \
    :app:assembleDebugAndroidTest \
    :fixture:assembleDebug \
    :fixture:lintDebug \
    :fixture:assembleDebugAndroidTest \
    "$@"
