# Contributing

Small, focused pull requests are easiest to review. Explain the user-visible problem, preserve existing data and cancellation behavior, and include relevant validation. For a significant UX, storage, provider or permission change, discuss the intended behavior first. Use synthetic text/audio and localhost test transports; a contributor must not need a real API key to run the automated checks.

## Build and test

Use JDK **17 or 21**, Python **3**, the committed Gradle **8.13** wrapper, Android API **36** and Build Tools **35.0.0**. Versions and checksums are in [the toolchain guide](docs/toolchain.md). Android Studio can use a locally installed SDK; keep `local.properties` untracked. On Unix:

```sh
export ANDROID_HOME=/absolute/path/to/android-sdk
bash scripts/doctor.sh
bash scripts/check.sh
```

On Windows use `gradlew.bat` for the corresponding tasks (assembleDebug, lintDebug, testDebugUnitTest, assembleDebugAndroidTest and the fixture tasks listed in scripts/check.sh), or run the Bash script in a configured Bash environment. Do not replace the wrapper or dynamically update pinned dependencies while fixing an unrelated problem.

The optional [cloud SDK setup guide](docs/cloud-environment.md) includes an installer. Read it first and explicitly accept SDK terms yourself before setting its consent variable. Do not pipe automatic acceptance into sdkmanager. Existing SDK installations do not need reinstalling.

`scripts/check.sh` builds the debug app and separate cross-app fixture, runs JVM tests and lint, and compiles Android test APKs. It **does not run instrumentation tests**. With an available emulator/test device:

```sh
adb devices
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

State the device/API and the actual test results. API 26–32 tests do not establish Android 13+ accessibility input-connection behavior. Run relevant cross-app fixture tests on a supported device for insertion changes; test focus/caret changes, cancellation, composition, sensitive fields and duplicate prevention. An unavailable emulator scenario must be reported as unrun, not passed. Never connect a personal device or send paid provider requests without its owner's explicit agreement.

## Data and screenshots

Read the architecture and privacy/diagnostic contracts before changing capture, storage, network calls, clipboard or Accessibility. Keep recognised and processed text separately recoverable. Do not add automatic uploads/retries, a full-field replacement/paste fallback, Send, or editor adoption after a history retry. Clear fixture data after tests.

Screenshots should show the actual app with disposable synthetic content; label test responses and platform limitations. Inspect pixels for keys, account identifiers, notifications and private text before committing an image. Public bug reports must not contain personal audio, raw provider bodies or unrestricted logcat. The app's manually exported diagnostic ZIP uses fixed event codes, but review any report/attachment before posting.

## Pull requests and security

Use a separate branch and explain what changed, why and how it was tested. Include migration/compatibility risks and any unrun checks. Keep commits attributable without publishing a personal email unless you intend it to be public; GitHub's noreply identity can be configured locally. Do not post private ChatGPT/Space links in repository discussions.

The repository's Android workflow checks PRs with read-only contents permission. The separate fixture and debug endpoints are development tools, not release features. Signing keys and release publication are maintainer-only manual operations; do not add secrets or enable the old signing proposal as part of a contribution. For vulnerabilities, follow [SECURITY.md](SECURITY.md) instead of posting exploit details publicly.
