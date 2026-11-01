# Architecture and extension points

One Android application module and a separate validation fixture. Compose renders immutable state and emits actions. There is no dependency-injection framework, backend, replacement keyboard, automatic retry or automatic submit.

## Ownership

`DictationSession` owns recording, transcription, credential state and an explicitly closeable child coroutine scope. Its commands and transitions run on its owner's serial dispatcher (Main in production). It has no Activity, ViewModel or Accessibility dependency. `cancel()` stops audio/network and discards retry audio while preserving the draft; `close()` is terminal, also cancels key loading and clears credential references. Late callbacks cannot reopen a closed owner.

`DictationViewModel` is the Activity lifecycle adapter. Activity stop finalizes/transfers local Record audio to process recovery; explicit Back cancels/discards audio; ViewModel clearing closes the session. `FloatingCapture` owns one explicit bubble tap and its original editor while local Record starts or a Live key loads/audio runs. The AccessibilityService owns Android windows, permissions, foreground notification and native connection. Closing/cancelling/disabling/unbinding closes the capture; no UI ViewModelStore is created in a service.

`DictationSessions` is the application composition root. HTTP clients and the normal encrypted key repository are shared. Each session receives fresh PCM/recorder ownership; file recording is allocated only when needed. `VoiceDictationApplication` restores the private, backup-excluded `RecordingRepository` at startup, including service/setup/live-only entry points. It imports legacy cache audio before any cleanup. No recorder or network request is opened by restore. Per-session `RecordingRetention` adapters own submission tokens; the repository owns durable audio/text.

## Ports and asynchronous boundaries

- `AudioRecorder`, `PcmSource`, `Transcriber`, `RealtimeTranscriber`/`LiveSession`, and `ApiKeyStore` are injectable boundaries. Existing instrumentation factories remain in-process only.
- Connection acquisition is a suspend port; the production adapter performs cold Request/OkHttp setup on IO and cancels a socket if cancellation wins its dispatcher return. The owner also cancels late-returned setup results. Live callbacks enter a bounded per-session event queue after the returned connection is owned, so an inline Ready callback cannot be lost. Terminal/cancel/error paths invalidate the generation, close the queue, cancel deadlines and attempt every resource cleanup independently. Old file-recorder limit callbacks are generation checked too.
- `PcmCaptureWorker` gives each run its own cancellation flag and capture driver. The Android driver uses non-blocking reads. Stop signals the worker without a main-thread join; only that worker stops/releases its driver in `finally`. An old worker cannot adopt a new run's flag or release a new capture. A broken driver that never returns remains owned by its old worker; arbitrary threads are never forcibly killed.
- `FloatingCapture` validates editor identity/caret, keyboard visibility, unlock, feature state and permissions before Record starts immediately. Live revalidates after its asynchronous key read and before starting audio. Periodic focus checks also cover key loading.
- `FinalTextInsertion` uses a captured `EditorConnection` port. It rechecks all environmental gates after suspended preflight, immediately before one commit. `AndroidEditorConnection` performs blocking bounded surrounding-text reads on IO. Editor policy and one-attempt insertion remain conservative; an attempted but unverified write settles silently without an error or retry, with text retained passively; no retry, rollback, full-field replacement or submit occurs.

## Credentials and data

`AndroidApiKeyStore` retains the existing AES-GCM/Android Keystore format, random IV, explicit Save/Forget, backup exclusions and namespace isolation. `SerializedApiKeyStore` serializes whole crypto/preferences operations across consumers of the same namespace, preventing a concurrent Forget from being overtaken by an earlier Save. Normal and synthetic fixture namespaces use independent locks. Storage executes on IO. Credentials never enter SavedState, logs, clipboard or artifacts; state diagnostics redact credentials and dictation text.

File mode uses private mono AAC/M4A (24 kHz, 48 kbps), a thirty-minute safety guard and existing file/upload size limits. Live mode uses PCM16 LE mono, 24 kHz, session acknowledgement before microphone start, bounded socket/event queues, ping liveness, explicit connect/finalization deadlines and item-based partial/final reconciliation. Transport protocol and user-visible modes are unchanged.

## Adding polishing work

Change Compose layout/copy in `DictationScreen` without owning audio there. Add state transitions to `DictationSession` with port-based tests. Add platform integrations in Android adapters/composition root. Keep editor classification in `EditorPolicy` and insertion safety in `FinalTextInsertion`; test suspension/race boundaries before extending auto-insertion. Update fixture fields/keyboards for compatibility tests rather than giving product instrumentation privileges.

The debug fixture is a separate UID with harmless fields and a local deterministic WebSocket server. Debug-only opt-in uses a synthetic encrypted credential namespace. Release builds exclude fixture configuration and alternate endpoint entry points. No paid API calls are part of validation.

See [review and validation](architecture-review.md), [floating contract](floating-contract.md), and [OpenAI contract](realtime-contract.md).

See [VD003 local Record ordering and temporary ownership](immediate-record.md). The existing recovery collection is shared by main and floating; VD004 adds durable storage and explicit per-record recovery. It does not add automatic uploads. VD008 adds the shared history sheet and an independently owned playback adapter.

See [durable recording transactions and retention](durable-recordings.md). Both successful audio and transcript remain until explicit discard; no count/age eviction is active.

See [history and playback ownership](recording-history.md).
