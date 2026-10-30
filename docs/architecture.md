# Architecture and scope

One Android module. The stateless Compose screen renders `DictationState` and emits callbacks; the ViewModel owns guarded main-thread transitions. No dependency injection framework, backend, OAuth, service, overlay, accessibility or keyboard integration is added.

## Two recording paths

File mode uses foreground `MediaRecorder` with mono AAC/M4A at 24 kHz / 48 kbps. Private cache, 30-minute internal safety guard, 20 MB file guard, 24 MB upload validation beneath the documented 25 MB API limit. At this encoding 30 minutes is about 10.8 MB, so no file chunking is necessary. Safety stop processes all captured audio. A visible elapsed-only timer has no countdown or limit label. `OpenAiTranscriber` makes a cancellable multipart HTTPS request using `gpt-transcribe`, bounded JSON response size and a 10-minute processing timeout. Failed uploads retain their recording for explicit retry; no automatic replay occurs.

Live mode connects `OpenAiRealtimeTranscriber` to the dedicated transcription WebSocket, sends session configuration and waits for its acknowledgement before starting `AndroidPcmSource`. AudioRecord emits PCM16 little-endian, mono, 24 kHz chunks from a worker thread. The socket queue is bounded; backpressure, disconnect or protocol failure closes it and stops capture. Client speech/silence detection commits at a pause, with a 15-second turn guard. Stop drains capture, commits the final buffer and waits up to 30 seconds for all committed completions; very short buffers are padded to the protocol minimum. A connection timeout prevents starting a microphone against an unconfigured session.

`LiveTranscript` matches `item_id`, deduplicates event IDs, replaces partials with final text and follows committed predecessor links across out-of-order completions. Cancellation invalidates the generation so late frames cannot overwrite state. Reconnect is a new explicit session: previously received words remain, unfinished words stay marked preliminary, and audio is not automatically replayed. Mode changes are disabled during work or pending file retry.

`MainActivity` requests microphone permission only from Record. Back/onStop/configuration changes cancel work and release microphone/network resources. There is no foreground service, so background microphone restrictions are respected by keeping capture in the visible Activity. Copy writes transcript text only, with an API 33+ sensitive clipboard marker.

## Key persistence

`AndroidApiKeyStore` stores AES-GCM ciphertext and random IV in app-private preferences. Its AES key remains in Android Keystore; cryptography and disk operations run on the IO dispatcher. The key field is masked and the window uses FLAG_SECURE. Plaintext never enters source, logs, saved instance state, backup or clipboard. Save is explicit; editing the field does not silently replace a previously saved key. Forget removes both payload and Keystore alias and clears the field. Failed storage operations report an error rather than claiming success.

Initial load disables actions until complete; Save/Forget are serialized by UI state. Persisting a key does not preserve the transcript across process death. Hardware-backed Keystore is device dependent; this app does not claim stronger guarantees than Android provides. Backup/device-transfer exclusions remain explicit for every app-data domain.

Tests inject providers only through an internal in-process factory, never through exported intents or user-configurable endpoints. Key tests use separate namespaces so they never read or mutate a real saved credential. Screen capture is enabled only inside synthetic-data instrumentation; the user build remains secure. Live capture creates no audio files. File recordings surviving an OS kill are cleaned on next startup; deletion is ordinary filesystem deletion, not secure erasure.

See [official contract](realtime-contract.md) and [verification](verification.md) for reproducible results and unverified live-provider/device coverage.
