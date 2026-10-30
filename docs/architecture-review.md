# Architecture review — 0.3.1

The refactor addresses concrete findings in the working 0.3 prototype and prepares further UI/editor work. The baseline findings and refactor were independently inspected. Configured Codex review completed without findings on architecture head `8d6dcf5`; the cleanup PR records its own exact-head review and CI. A successful user run on Samsung One UI 9 validates the earlier build, not this refactor.

| Finding | Change and regression coverage |
| --- | --- |
| A floating live-only ViewModel constructed a file recorder whose constructor purged every retry file. | Deferred file allocation; one process-start orphan sweep, also for live/setup-only launches. A second owner preserves an existing retry file; a two-process instrumentation check covers crash-orphan cleanup without opening a recorder. |
| Activity and service duplicated the production graph; service used UI ViewModelStore for lifecycle. | Shared composition root and closeable `DictationSession`; thin Activity ViewModel and explicitly owned `FloatingCapture`. |
| Delayed key load could start microphone after an eligible field/caret changed. | Fresh start guard after key load; loading participates in focus cancellation. Tests suspend loading and change field, caret, keyboard, lock, feature and grants, or close the owner. |
| Lock/keyboard/disable could change during insertion's IO preflight. | Port-based single-attempt insertion checks environment at the write boundary. Suspended-preflight tests prove zero commits on invalidation. Unconfirmed commits never retry. |
| Shared PCM running flag plus a timed main-thread join could revive an old worker or release its driver in use. | Per-run flag/driver, non-blocking Android reads and worker-owned cleanup. Blocked-worker cancel/restart tests prove isolation and one release off the caller thread. |
| Inline Ready could beat assignment of the returned connection; Finished left late callbacks valid. | Bounded ordered events, explicit terminal generation and independent cleanup. Four new tests failed before the fixes and pass afterward. |
| Cold HTTP/WebSocket setup still occupied Main, observed in a real ANR stack. | Suspend connection acquisition on IO, owned setup job and cancellation-safe resource transfer; suspended setup, timeout and late-failure regressions. |
| Separate key consumers could interleave Save/Forget across Keystore and preferences. | Whole-operation namespace serialization. A blocked Save cannot finish after a concurrent Forget and recreate the saved key. |

No permission broadening, paid calls, credential migration, hidden start, automatic reconnect/retry, field replacement or submit was added. The APK version is 0.3.1 (code 4) to distinguish this candidate from the earlier delivered build. Debug signing may differ between environments; production update signing remains a separate release concern.

## Validation

- Full `scripts/check.sh`: PASS; strict app/fixture lint, debug APK, both instrumentation APKs and **70 JVM tests, 0 failures/errors, 0 skipped**.
- Minified release build: PASS. Manifest inspection excludes debug fixture setup; debug APK v2 signature verifies.
- Final API 28 in-app runtime: **11/11 PASS** after the async setup and startup-cleanup changes (226.092 s). A separate two-process orphan test passed both seed and next-process cleanup stages without opening a recorder.
- Final API 28 floating runtime: **2/2 PASS** (205.651 s). Bubble → live PCM/partial → Stop → explicit Copy; the original editor text stays unchanged, microphone service stops, and Cancel/explicit restart passes. The fixture observed one connection and 326,318 PCM bytes. API 28 intentionally uses Copy, so this does **not** validate native insertion.
- Real emulator video, screenshots and machine logs were delivered separately through Library. Generated evidence is intentionally excluded from Git; this document retains the verification summary. The video demonstrates synthetic localhost transcription and API 28 Copy, not native insertion.
- Earlier runtime failures were diagnosed rather than counted as passes: an OS permission-dialog run hit System UI ANR/display override; the initial floating run hit a product Main-thread connection-setup ANR. Resetting the display and moving cold client/connection setup to IO resolved the applicable reruns above.
- Android 16 booted in the bounded attempt, then APK installation failed with `Failure calling service package: Broken pipe (32)`. The installed version could not be confirmed; no cross-app test or successful video started. API 33+ insertion on this refactor remains **UNVERIFIED**. The emulator was stopped. Earlier prototype/user success is not counted as refactor validation.
- Architecture-head CI passed at `8d6dcf539c1bd44e4cd7dc2dad60a4bdc998f35f` ([run 41117656248](https://github.com/IlyasYOY/android-voice-dictation/actions/runs/41117656248)). [Configured Codex review](https://github.com/IlyasYOY/android-voice-dictation/pull/1#issuecomment-6532040273) completed on that head with no findings. Subsequent repository cleanup removes generated evidence only; its own checks/review are recorded in the PR.

A separate final diff pass checked lifecycle/terminal paths, blocked preflight gates, retry-file ownership, same-namespace storage transactions, non-revival of capture workers, and preservation of existing transport/permission/no-submit boundaries. No additional product behavior changes were introduced by that pass. Runtime failures will remain explicit; compiled tests are not runtime passes.
