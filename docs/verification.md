# Prototype 0.3.0 verification — 2026-10-30

## Completed so far

- `scripts/check.sh`: PASS — debug APK, strict app/fixture lint, 43 JVM tests and compilation of both instrumentation APKs. Four new pure tests cover sensitive editor exclusion, stable original caret/session, selection/composition/legacy Copy fallback, and insertion confirmation. Existing real multipart HTTP/WebSocket and half-open regressions remain included.
- `:app:assembleRelease`: PASS — release build excludes the debug fixture activity and fake endpoint configuration.
- Existing in-app instrumentation on the API 28 software emulator: **11/11 PASS, no skips/failures**. This regression run covers real recording/PCM/network/UI, permission denial, cancellation/lifecycle, retries and encrypted key storage. The later app change adds diagnostics only in explicitly enabled synthetic fixture mode.
- **Actual Android 16/API 36 cross-app end-to-end: 1/1 PASS** (`OK (1 test)`, 210.636 seconds on software emulation). Product app runs without instrumentation; the separate fixture UID controls the visible system permission/special-access dialogs and local deterministic server. Existing user-granted access is retained between reruns.
- The successful cross-app flow: AOSP keyboard visible → circle → wait beyond recent-Activity grace → actual microphone AudioRecord PCM over actual WebSocket → live partial visible while original field unchanged → Stop → final native insertion → keyboard dismissal removes circle. Server received **216,000 PCM bytes**, one connection. `Before  after` became `Before voice test after`. Final service dump shows `startRequested=false` and no ongoing microphone foreground state.
- Four actual screenshots are in [floating evidence](runtime-evidence/floating/). They show a clearly labelled synthetic fixture and test transcription; no real API key, real account, user message, paid API call or OpenAI recognition was used.

## Verification in progress

The complete cross-app safety matrix, second IME, video, final exact-head CI/artifact identities and native Library delivery are being completed. Do not treat compiled tests or earlier failed automation attempts as runtime passes. The historical [0.2 report](verification-v0.2.md) documents earlier in-app evidence and its limits.

The first Android 16 onboarding attempts encountered a System UI ANR and automation issues (uppercase native buttons, permission-view recreation, missing package visibility in the fixture). A later cache-refresh test accidentally used default UiAutomation flags that suppress accessibility services; it was stopped and corrected to preserve FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES. These attempts were not counted as passes. The successful end-to-end run above preceded the cache experiment and remains valid.

## Contract and limits

See [floating Android contract and primary sources](floating-contract.md) and [OpenAI transport contract](realtime-contract.md). Automatic insertion requires Android 13+ and a compatible, stable, ordinary text editor; selected/composing/unsupported fields offer Copy, sensitive/password/numeric fields are excluded. Universal OEM/keyboard/editor compatibility is not promised. Live OpenAI recognition/authentication/billing, physical-device audio quality and paid API use remain unverified. No replacement keyboard is required by the product; the fixture IME is validation-only. Repository remains private, main unchanged, PR #1 draft/unmerged.
