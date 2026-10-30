# Architecture and scope

One Android module, three production components plus a stateless Compose screen:

- `DictationViewModel`: immutable `StateFlow<DictationState>`, explicit idle/recording/transcribing/result/error phases, guarded taps, cancellable request and recording timer, audio cleanup, explicit retry. Key and text are memory-only; ViewModel retains them across Activity recreation, never through process death. Main-thread actions serialize state changes. Generation invalidation prevents late requests replacing state after cancellation.
- `AndroidAudioRecorder`: foreground-only `MediaRecorder`, microphone → AAC/MPEG-4 M4A in private cache, max 120 seconds / 20 MB, stop/cancel/release and process-death orphan cleanup on next startup. No service, background microphone, overlay, accessibility or keyboard permissions.
- `OpenAiTranscriber`: cancellable OkHttp multipart POST, current `gpt-transcribe` model, JSON `text` response. HTTPS production endpoint, no redirects or automatic retries, bounded response size, connect/call deadlines. API failures expose HTTP status categories, never untrusted provider messages or credentials. Endpoint/recorder seams let tests use an actual local server without touching paid API access.
- `MainActivity`: explicit microphone permission launcher, app settings action for permanent denial, lifecycle/Back cancellation, clipboard with sensitive marker on API 33+, secure window. `onStop` always aborts active work and discards retry audio. No background/foreground service is needed or started. System permission grant alone cannot start a recording after the Activity was stopped.
- `DictationScreen`: state and callbacks only, localized strings, scrollable responsive layout, separate Stop/Cancel, visible timer and progress. Entering a key alone makes no network calls.

The in-process test factory is internal and never controllable through exported intents. Instrumentation installs an actual recorder and local HTTP transcriber, labels synthetic server evidence and temporarily clears the secure-window flag only to capture synthetic-data UI. The APK ships no fixture key, fake API mode, runtime endpoint settings, secret persistence, session credential retrieval or analytics.

The old preview example repository and domain draft scaffold are removed: returned text now comes only through the transcription boundary. Audio from a failed request remains privately available until explicit retry/cancel/new recording/lifecycle stop; cancellation cannot recall audio already transmitted to a provider. The app's backup and transfer rules exclude all data domains. Cache deletion is best effort filesystem deletion, not secure erasure.

## Verification

State tests cover permission/key gates, repeated taps, successful result/edit/clear, retry, cancellation/late completion, recorder failure, forgotten key and timer limit. MockWebServer tests exercise actual HTTP bytes, Unicode, malformed/empty/typed responses, API status, no-network invalid input, timeout and cancellation. Android tests run real microphone recording on an emulator, system permission deny/grant, error/retry, clipboard, cancellation, background navigation and Activity recreation. Runtime evidence proves UI and plumbing, not OpenAI speech accuracy. See `verification.md` for exact executed results and remaining runtime coverage gaps.
