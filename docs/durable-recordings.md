# VD004 durable recording foundation

PR4 merged at `e68bcfe0cd7b9ed9bf50e6937b61be738ea64635`. VD004 is a separate branch/PR. The existing Soft Current interface, explicit Record/Live actions, microphone permissions and one-attempt editor insertion remain the product contract. Swipe history is the next milestone.

## Storage contract

Use an app-private, backup-excluded directory outside cache. Each random-ID recording owns its audio and versioned metadata: creation/update time, duration, closed status/failure enums, transcript and submission generation. No credential, endpoint, editor token or surrounding field text is recording metadata. No connection listener, boot worker or automatic upload is introduced.

Metadata is staged, synced, atomically replaced, then its directory is synced. Capture is registered before writing audio. Stop finalizes/syncs the complete file before submitting. Successful response metadata is committed atomically. Successful audio is retained for future playback until explicit discard. Explicit retry obtains a unique attempt token; cancellation invalidates it, so a late response cannot commit over a newer retry or discard. Completed text remains passive after restart, never reinserted into a previously focused editor.

Startup loads committed data and changes interrupted capture/submission to an explicit interrupted state. It does not upload. Unknown/corrupt metadata and associated audio are preserved as unavailable rather than silently swept. Explicit deletion writes a tombstone before removing bytes; interrupted deletion is resumed locally, without network work.

Upgrade imports nonempty legacy cache recordings before the old orphan sweep can delete them. Destination identity is stable across interrupted import. Source audio is removed only after durable destination bytes and metadata. Records owned by the new ledger are never subjected to the old cache sweep.

A force-killed Android MediaRecorder may leave an unfinished M4A container; bytes and interrupted status are retained, but valid recognition of a never-finalized container cannot be promised. Completed Stop recordings must survive process death and device restart. Live remains a network stream, not an offline recording mode.

## Approved retention policy

The user confirmed on October 31: keep BOTH successful audio and transcript/status/time for future playback. Pending/failed recordings also remain until explicit discard. No count/age eviction is implemented; the eventual configurable limit and eviction policy require a separate agreement. Existing explicit Cancel/Back still discard the active recording as requested. A user may clear the current editable draft without deleting stored recordings; Delete recording removes that saved audio/text and its linked draft.

VD004 adds a small real Saved recordings screen with explicit Retry/Copy/Delete, accessible from the existing main button and holding the idle keyboard control. It keeps the Soft Current palette and supports scrolling/large text. It does not add swipe history, playback controls or fake screens. Those follow later. Live stores received text checkpoints and its final transcript; its existing network stream does not create a local audio file.

## Validation

115 JVM tests cover committed Stop audio, interrupted capture/upload restoration, failed response commits, approved successful-audio retention, stale/concurrent attempt rejection, idempotent interrupted migration, unknown metadata, credential-free schema, active capture deletion protection, deletion-tombstone interruptions and restored draft-source deletion linkage. The failed-disk-commit regression verifies one explicit retry saves the already-received response without another network call. Strict app/fixture lint, debug/test APKs and minified release are required by `scripts/check.sh` and CI.

Actual API28 filesystem instrumentation passed: atomic rename/directory sync, reopened audio/transcript/draft and explicit deletion. An actual accepted 0.5.0 capture produced 82,615 bytes, SHA-256 `7a9b682601fe704e768b09027d30993a5e2c4aacf8e11e6eb98465dca7469f85`. Installing 0.6.0 with `adb install -r` and no data clear passed legacy-import UI validation: Audio saved, manual Retry, zero startup HTTP/WebSocket requests. After a real guest reboot those exact bytes/hash remained and the ledger restored INTERRUPTED. The subsequent UI-retry phase aborted before retry because Android system_server crashed in SurfaceControl finalization; complete legacy reboot-to-UI-retry is not claimed passed.

A fresh API28 guest is used separately for main-screen capture/retry/process-death/deletion checks. Do not treat its data as continuity of the legacy upgrade recording. The fixture methods `u/v/w` are host-orchestrated upgrade/reboot phases; `x` follows the separate `OfflineRecordRuntimeTest` success. They intentionally skip optional Accessibility/microphone setup when only reading/retrying/deleting saved audio.

Exact final results, screenshot provenance and manual acceptance steps accompany the versioned APK. No generated media/logs are committed. Samsung acoustic first-word capture and API33+ insertion remain unverified by this software-emulated filesystem test. Deliver actual screenshots and a concise manual checklist; no video.
