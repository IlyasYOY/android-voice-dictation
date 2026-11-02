# Durable audio and text

`noBackupFilesDir/recordings-v1` owns per-record metadata/audio plus linked main/floating text drafts. The repository is process-owned and shared by main, overlay and manual recovery. Metadata is replaced atomically and directory-synced; a generation/attempt claim rejects stale upload completion. Credentials and editor tokens never enter this store.

Record allocates a CAPTURING row before starting the single AudioRecord/AAC encoder. Stop finalizes AAC/M4A, checkpoints duration, then requests transcription. Both successful audio/transcript and failed/interrupted audio remain until explicit deletion. Unknown/staged failed metadata is preserved as UNAVAILABLE and counted, without silently freeing occupied capacity. Received text remains copyable if its final commit fails; retrying a known response does not make another paid request.

Startup converts CAPTURING/UPLOADING ownership to interrupted state. It does not start a microphone, upload, reconnect or insert. A force-killed capture/encoder may leave an unfinished M4A container: bytes/status are preserved, but valid recognition cannot be promised for an unfinalized container.

Delete uses durable tombstones and invalidates linked drafts so deleted text cannot reappear as an orphan. Storage failures must not overwrite a newer dirty in-memory draft. Blank temporary editing retains source identity; explicit Clear detaches the draft without deleting the recording. No count/age eviction or automatic cleanup of retained data is enabled.

Old nonempty cache audio is imported before removing its source. Old metadata `kind=LIVE` is read only as historical text, preserving transcript/status/partial/draft links without fabricating audio. There is no new producer for this kind. Empty in-memory transport admissions from old processes cannot persist across upgrade; nonempty checkpoint/staging directories remain. See [migration](record-only.md), [capacity](history-count-limit.md) and [history](recording-history.md).

New checks must distinguish JVM/real-filesystem/Android execution. Historical accepted checks do not validate new changes automatically; no runtime evidence is claimed when the current device is unavailable.

A Stop timeout keeps the unfinished worker’s capture reservation until actual resource cleanup. Only then can recovery release it for Retry/Delete. Explicit Cancel discards its own clip after cleanup. See [audio-reactive capture](audio-reactive-current.md).
