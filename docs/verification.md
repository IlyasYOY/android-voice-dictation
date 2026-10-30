# Prototype 0.3.0 verification — 2026-10-30

Historical candidate report. For the current 0.3.1 refactor, use the [architecture review and validation](architecture-review.md).

## Completed so far

- `scripts/check.sh`: PASS — debug APK, strict app/fixture lint, 43 JVM tests and compilation of both instrumentation APKs. Four new pure tests cover sensitive editor exclusion, stable original caret/session, selection/composition/legacy Copy fallback, and insertion confirmation. Existing real multipart HTTP/WebSocket and half-open regressions remain included.
- `:app:assembleRelease`: PASS — release build excludes the debug fixture activity and fake endpoint configuration.
- Existing in-app instrumentation on the API 28 software emulator: **11/11 PASS, no skips/failures**. This regression run covers real recording/PCM/network/UI, permission denial, cancellation/lifecycle, retries and encrypted key storage. The later app change adds diagnostics only in explicitly enabled synthetic fixture mode.
- **Actual Android 16/API 36 cross-app end-to-end: 1/1 PASS** (`OK (1 test)`, 210.636 seconds on software emulation). Product app runs without instrumentation; the separate fixture UID controls the visible system permission/special-access dialogs and local deterministic server. Existing user-granted access is retained between reruns.
- The successful cross-app flow: AOSP keyboard visible → circle → wait beyond recent-Activity grace → actual microphone AudioRecord PCM over actual WebSocket → live partial visible while original field unchanged → Stop → final native insertion → keyboard dismissal removes circle. Server received **216,000 PCM bytes**, one connection. `Before  after` became `Before voice test after`. Final service dump shows `startRequested=false` and no ongoing microphone foreground state.
- Captures and machine logs were delivered separately through Library and are excluded from Git. They use a clearly labelled synthetic fixture; no real API key, real account, user message, paid API call or OpenAI recognition was used.

- The updated controller repeats the Android 16 end-to-end successfully: **1/1 PASS**, 234.352 seconds, **124,800 PCM bytes**, original sides preserved and final foreground service stopped. The machine log was delivered separately. The historical panel-closed screenshot was misleading; the refactor delivery provides a genuinely unobscured API 28 field, which demonstrates Copy only.
- Android 16 cancellation/explicit restart: **1/1 PASS**, 239.12 seconds; Cancel leaves the field unchanged with no automatic connection/replay, the next explicit start inserts final text once. This run preceded the later rendering/scheduling optimization; it is historical evidence, not refactor validation.
- Android 9/API 28 floating workflow: **1/1 PASS**, 73.562 seconds, **268,800 PCM bytes**. Copy fallback is explicitly shown and original field stays unchanged. A separate recorded repeat passed in 115.264 seconds with 192,000 PCM bytes. These precede the final rendering optimization. Captures and logs were delivered separately.
- The API 28 recording is actual screenrecord/H.264 with no audio; its 39-second excerpt (80–119 seconds) decodes successfully. No image/mockup UI is substituted for runtime evidence.

## Historical verification limits

The extended safety scenarios passed nine of ten checks across the prototype runs; second-IME activation remained unverified. This is a historical prototype report. The current refactor results and modern-runtime gap are recorded in the architecture review; compiled tests and earlier failed automation attempts are not runtime passes. The historical [0.2 report](verification-v0.2.md) documents earlier in-app evidence and its limits.

The first Android 16 onboarding attempts encountered a System UI ANR and automation issues (uppercase native buttons, permission-view recreation, missing package visibility in the fixture). A later cache-refresh test accidentally used default UiAutomation flags that suppress accessibility services; it was stopped and corrected to preserve FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES. These attempts were not counted as passes. The successful end-to-end run above preceded the cache experiment and remains valid.

## Contract and limits

See [floating Android contract and primary sources](floating-contract.md) and [OpenAI transport contract](realtime-contract.md). Automatic insertion requires Android 13+ and a compatible, stable, ordinary text editor; selected/composing/unsupported fields offer Copy, sensitive/password/numeric fields are excluded. Universal OEM/keyboard/editor compatibility is not promised. Live OpenAI recognition/authentication/billing, physical-device audio quality and paid API use remain unverified. No replacement keyboard is required by the product; the fixture IME is validation-only. Repository visibility remains private; current PR/merge status is recorded in GitHub, rather than this historical candidate report.
