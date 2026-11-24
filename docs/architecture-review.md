# Review boundaries

Record capture, file upload, history transactions, key staging and editor insertion retain separate ownership. Review asynchronous start/stop/close, late callbacks, persistence failures, explicit retry and final-write revalidation. Validate history settings before microphone allocation. Evict only after a new recording is durably kept.

Removal of Live eliminates realtime transport/capture and mode-selection entrypoints. Only old-format text metadata is decoded for compatibility; there is no producer or executor for it. Review [migration](record-only.md) alongside the unchanged privacy/key storage format and final insertion tests.

Local JVM/lint/build and compiled Android tests are reported separately from actual Android/device checks. CI does not run an emulator; unavailable runtime is an explicit limitation.
