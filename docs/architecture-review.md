# Architecture review — 0.3.1

The refactor addresses concrete findings in the working 0.3 prototype and prepares further UI/editor work. The baseline findings were independently inspected; this change also gets a second diff review and final CI checks. A successful user run on Samsung One UI 9 validates the earlier build, not this refactor.

| Finding | Change and regression coverage |
| --- | --- |
| A floating live-only ViewModel constructed a file recorder whose constructor purged every retry file. | Deferred file allocation; one process-owned orphan sweep. A second owner preserves an existing retry file. |
| Activity and service duplicated the production graph; service used UI ViewModelStore for lifecycle. | Shared composition root and closeable `DictationSession`; thin Activity ViewModel and explicitly owned `FloatingCapture`. |
| Delayed key load could start microphone after an eligible field/caret changed. | Fresh start guard after key load; loading participates in focus cancellation. Tests suspend loading and change field, caret, keyboard, lock, feature and grants, or close the owner. |
| Lock/keyboard/disable could change during insertion's IO preflight. | Port-based single-attempt insertion checks environment at the write boundary. Suspended-preflight tests prove zero commits on invalidation. Unconfirmed commits never retry. |
| Shared PCM running flag plus a timed main-thread join could revive an old worker or release its driver in use. | Per-run flag/driver, non-blocking Android reads and worker-owned cleanup. Blocked-worker cancel/restart tests prove isolation and one release off the caller thread. |
| Inline Ready could beat assignment of the returned connection; Finished left late callbacks valid. | Bounded ordered events, explicit terminal generation and independent cleanup. Four new tests failed before the fixes and pass afterward. |
| Separate key consumers could interleave Save/Forget across Keystore and preferences. | Whole-operation namespace serialization. A blocked Save cannot finish after a concurrent Forget and recreate the saved key. |

No permission broadening, paid calls, credential migration, hidden start, automatic reconnect/retry, field replacement or submit was added. The APK version is 0.3.1 (code 4) to distinguish this candidate from the earlier delivered build. Debug signing may differ between environments; production update signing remains a separate release concern.

## Validation

- Full `scripts/check.sh`: PASS; strict app/fixture lint, debug APK, both instrumentation APKs and **66 JVM tests, 0 failures/errors, 0 skipped**.
- Minified release build: PASS. Manifest inspection excludes debug fixture setup; debug APK v2 signature verifies.
- Initial API 28 runtime: 11 executed, 9 passed, 2 failed (permission-dialog discovery and physical Back). Actual capture showed a System UI ANR covering the test; a retained logical display override also made manual recovery taps miss. Diagnostic logs are preserved. The display was reset and the ANR dismissed; one clean rerun is in progress.
- Android 16 insertion/cancellation, exact-head CI and automatic review: pending final candidate checks. Previous user/runtime success is not counted as validation of this refactor.

A separate final diff pass checked lifecycle/terminal paths, blocked preflight gates, retry-file ownership, same-namespace storage transactions, non-revival of capture workers, and preservation of existing transport/permission/no-submit boundaries. No additional product behavior changes were introduced by that pass. Runtime failures will remain explicit; compiled tests are not runtime passes.
