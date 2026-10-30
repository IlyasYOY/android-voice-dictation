# Prototype verification — 2026-10-30

The published cloud workspace was used. Main was fetched and remained at `3f4461a7547180d66c26682f84ccff18b9e2a3fc`. Existing shared SDK, JDK, caches, emulator scripts and onboarding configuration were preserved. No new SDK installation or license acceptance, paid API call, credential extraction, visibility change or merge was performed.

## Completed checks

- `bash scripts/check.sh`: PASS — debug APK, strict Android lint (warnings as errors), JVM tests and instrumentation APK compilation.
- JVM: **17 passing tests** (11 state/ViewModel, 6 real HTTP MockWebServer tests). Covers key/permission gates, repeated taps, recording/upload cancellation, draft preservation, timer limit, explicit retry, cleanup, HTTP status mapping, malformed/empty responses, exact multipart bytes/model/auth, Unicode, invalid input, network timeout and HTTP cancellation.
- `:app:connectedDebugAndroidTest` on the installed Android **9 / API 28 x86_64** software emulator: **6 passing instrumentation tests**, no skips. Test order sets up microphone denial before permission-granting scenarios.
- Real Android scenarios: system microphone denial → visible error → request again → grant → real recording/cancel; real MediaRecorder → actual HTTP POST → synthetic server transcript → edit → system clipboard → clear → forget key; API 500 → visible retry → success; explicit recording cancellation; app background cancels microphone; Activity recreation preserves session draft/key and cancels recording; Android Back cancels upload and rejects late result. Private recording directory is asserted empty after completion/cancellation.
- App screenshots are captured from the running Activity for idle, recording, transcribing, result, error/retry and permission denial. Test-only in-process setup clears `FLAG_SECURE` with a synthetic key and visible `TEST SERVER` / `PERMISSION TEST` notice. These are runtime evidence, **not OpenAI recognition evidence**. No user audio or credentials are included.

The original first runtime attempt passed system permission handling but exposed a test setup `NetworkOnMainThreadException` when resolving the local server URL inside the Activity factory. Moving test URL resolution to the instrumentation thread fixed it; the six-test suite then passed. The final evidence run also resets an inherited emulator display override to avoid black padding in captured screenshots. These failures were not hidden or counted as successful runs.

## Reproduce on a dedicated emulator

With an existing licensed SDK and a booted test device, run `./scripts/runtime-check.sh`. It installs both APKs, revokes this prototype's microphone permission, executes the two instrumentation test classes and pulls screenshots to ignored `captures/evidence`. This script changes permission state on the test device; do not point it at your everyday phone. Reports are in `app/build/reports/androidTests/connected/debug/` and `app/build/outputs/androidTest-results/connected/debug/`.

In the current workspace, source `/workspace/shared/android-env.sh` first. The emulator uses existing API 28 packages and runs without KVM. No saved environment was edited.

## Limits

- Live OpenAI authentication, billing, latency and recognition accuracy were **not tested**. All API success/error responses in automated tests are local synthetic responses; actual MediaRecorder bytes and actual HTTP serialization are exercised. User-supplied key plus separately configured billing/model access are required for real recognition.
- Runtime verified on API 28 only. API 26/36 physical-device coverage, modern microphone privacy toggles, vendor-specific behavior, screen readers, dark theme and large font remain unverified. API 36 target compilation and lint passed.
- Floating overlay entry, automatic insertion, accessibility service, keyboard and background recording are outside this implementation. The working path is in-app dictation and clipboard copy.
- Debug APK uses an Android debug signature; no store/release distribution is configured. Session text/key are lost after process termination. Process-death orphan audio is removed on next startup, not synchronously at OS kill.

The PR remains a draft; main is unchanged. The final delivery records the exact pushed SHA, CI run, APK checksum and native Library IDs separately, so a source document never claims its own future commit SHA.
