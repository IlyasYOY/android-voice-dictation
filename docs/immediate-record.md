# VD003: immediate local Record

Record starts the existing AAC/M4A recorder from the explicit, permission-authorized action. It does not wait for a key read or a connection, and it makes no transcription request before Stop. The recording state is published after the recorder starts; actual microphone hardware startup and first-word quality still require device validation. Live keeps its existing key/session-acknowledgement-before-capture behavior.

Stop closes local capture first. The one Stop-requested upload can await an already-running local key read; a missing key/storage/network error retains the complete file for an explicit retry. Loading a key or restoring connectivity does not retry a failed upload. Repeated Start/Stop/Retry actions cannot duplicate capture or an in-flight submission.

The existing process-owned recovery collection is shared by both entry points as `RecordingRecovery`. Main activity background/recreation and floating editor/window interruption finalize Record locally, cancel in-flight work and transfer only the file. New main recordings first transfer any previous retry file. Explicit Cancel/Back/discard retain their destructive audio policy. Credentials, microphone and editor ownership do not travel with a retained file; late results are generation-guarded. Manual recovery returns passive text and never inserts into a stale editor.

Saved recordings are available from the main screen and by holding the idle floating control. Both routes focus the existing explicit Retry/Discard section. This does not introduce permanent storage/history: the index remains in memory, files remain private cache, and process restart uses the existing orphan sweep. Durable storage/history is the subsequent milestone.

Tests use synthetic first/last sample markers, delayed/failing key ports and deterministic network failures to verify ordering, complete-file ownership, no automatic reconnect retry and cleanup. These tests do not establish recognition quality, real-device microphone latency or preservation of an actual spoken first word. Runtime evidence must name the tested commit/device and distinguish executed tests from compilation.
