# Prototype 0.2.0 verification — 2026-10-30

Historical report. Current refactor results and limitations are in the [architecture review](architecture-review.md).

Changes requested in the same private draft PR: elapsed-only timer with a generous internal guard, explicit encrypted key Save/Forget, and genuine microphone-streaming transcription transport in a separate Live mode. Existing environment onboarding, SDK, AVD and shared scripts were preserved. No new SDK installation/license acceptance, real credential setup, paid API call, public publication or merge was performed.

## Completed checks

- `bash scripts/check.sh`: PASS — debug APK, strict lint (warnings as errors), **39 JVM tests**, instrumentation APK compilation.
- JVM: 14 file/state/storage tests, 9 live-state tests, 5 transcript/turn/audio-policy tests, 6 actual multipart HTTP tests and 5 actual WebSocket tests. Covers duplicate events, reordered final items, new-session reset, partial preservation, invalid/empty responses, permission gates, cancellation, timeout, safety-boundary retry and storage failure.
- Half-open regression: the actual WebSocket peer keeps TCP open but stops reading/responding to ping. The same production client factory, with a shortened test interval, emits a bounded failure without reconnecting. Production ping interval is asserted as 15 seconds; an unanswered ping fails by the next interval. Separate state assertions verify that failure releases the microphone, exits the busy phase, preserves received words and closes the session. Connect and final-drain deadlines are also tested.
- `:app:connectedDebugAndroidTest` executed on the installed Android 9 / API 28 x86_64 software emulator: **11/11 passing, no skips**. The full eleven-test suite passed twice; the final UI run uses readable read-only live text and a pinned synthetic-evidence banner.
- Actual Android coverage: microphone denial/regrant; file record/upload/edit/clipboard/clear; HTTP error/retry; cancellation, background navigation, recreation and late-response rejection; actual AudioRecord PCM → actual WebSocket → partial → Stop → final; explicit reconnect after server close without auto-replay; guarded mode switching; encrypted Save/Restore/Forget, fresh random IV and authenticated rejection of tampered ciphertext.
- `scripts/key-restart-check.sh`: PASS — **three separate instrumentation invocations**, with `am force-stop` between phases: Save, restored key/Forget, then absent key on another process start. All three returned `OK (1 test)`. The fixture lives in a separate test namespace, never the user's credential store.
- Video instrumentation: **2/2 passing** — live partial/final and key Save/Restore/Forget UI. Actual `screenrecord`, no audio, H.264 320×640. Full capture ~157 seconds; the shared short clip is an explicitly trimmed 28-second live excerpt (seconds 26–54). Both MP4s decode successfully; representative frames were inspected.
- Local debug APK signature verified with apksigner (v2, one signer). Native Library upload uses this locally exercised APK, not an untested fabricated preview.

The initial ten-test Android attempt passed nine scenarios but timed out waiting for a mock abrupt socket cancellation to become visible. The deterministic server-close case now uses WebSocket 1011, and a separate real-network half-open heartbeat regression addresses silent/stalled connections rather than hiding that issue.

## Evidence and delivery

Eleven real screenshots, a short clip, full recording and machine reports were delivered separately through Library; generated proof is excluded from Git. Every evidence screen has a synthetic-server/key-test label; FLAG_SECURE is cleared only in test processes using synthetic credentials. User screenshots remain protected. The recording tests send actual encoded/PCM bytes to local servers, whose transcript responses are deterministic and synthetic. This proves transport/state/UI plumbing, not recognition quality.

The APK and evidence archive were delivered through Library; CI publishes generated APK/reports separately for seven days. Delivery records retain the exact pushed SHA, CI and Library identities, and local APK checksum. These external artifacts are preserved during repository cleanup.

## Reproduce

Use a dedicated emulator with an existing licensed SDK. Run `./scripts/check.sh`, `./scripts/runtime-check.sh`, then `./scripts/key-restart-check.sh`. The runtime scripts install test packages and change this prototype's permission/process state; do not target an everyday phone. In this workspace source `/workspace/shared/android-env.sh` first. No saved environment configuration was edited.

## Limits

- Live OpenAI recognition, authentication, billing, real-world latency, microphone quality, accents and long-form recognition accuracy remain **unverified**. No real API key or paid call was used. Official model/endpoint/schema sources are listed in [realtime-contract.md](realtime-contract.md).
- The thirty-minute boundary is exercised with a virtual clock; a continuous thirty-minute physical recording was not performed. Encoding/file-size arithmetic and safe full-recording retry are tested. File upload is compressed; live PCM has higher bandwidth and begins transmitting while recording, as stated in the app.
- Runtime verified on API 28 only; API 36 compiled/linted. Modern microphone privacy toggles, physical-device vendors, dark theme, large fonts and screen readers remain unverified.
- Floating overlay, automatic insertion, accessibility service, keyboard, backend credential exchange and background capture remain outside scope.
- Session transcript is memory-only. Explicit Save persists the encrypted API key; Forget deletes payload and Keystore alias. OS/device compromise and secure-erasure guarantees are outside this prototype. Process-killed file recordings are cleaned on next startup.
- Debug signing can differ between CI runs; the Library APK has the local cloud debug signature. Production updates need a stable release signature, which is not configured. Current PR/merge status is recorded in GitHub; this is a historical report.
