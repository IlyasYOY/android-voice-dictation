# Architecture and scope

This is a deliberately small, single-module scaffold. Packages mark responsibilities; extra Gradle modules, dependency injection frameworks and backend contracts would be premature.

## Current flow

`MainActivity` obtains `DictationViewModel` via an explicit factory. The ViewModel exposes an immutable `StateFlow<TranscriptDraft>`, observed with `collectAsStateWithLifecycle`. `DictationScreen` is stateless and emits edit/load-example/clear callbacks.

- **domain:** `TranscriptDraft` is pure Kotlin. Editing removes the example marker; clearing is idempotent.
- **data:** `ExampleTranscriptRepository` is a preview-only seam. Production uses a deterministic in-memory example. Tests inject a tiny fake. It is **not** the eventual transcription API.
- **ui:** ViewModel owns state; `SavedStateHandle` retains the temporary text/example marker through recreation. This is not durable note storage or a security boundary. Android may restore saved state; users should not assume clearing this screen securely erases underlying OS storage.

The default application ID is `io.github.ilyasyoy.voicedictation`. It is a reversible development identifier, not a claim of a registered Play Store listing.

## Future seams, not implemented contracts

When the first real feature is agreed, introduce separate recording and transcription interfaces with explicit cancellation and failure states. Decide audio format, local vs remote processing, consent, retention and costs before connecting a provider. Keep credentials outside the APK; the auth/billing design is intentionally undecided.

Android backup and device-transfer rules explicitly exclude all app-data domains, alongside `allowBackup=false`; see [Android backup rules](https://developer.android.com/identity/data/autobackup). This is backup configuration, not a promise of secure erasure or durable note storage.

No microphone, internet or overlay permission exists. No foreground/background service, networking library, API key field or paid endpoint exists. The disabled recording button must not be wired to fake success.

## Testing

- JVM tests: empty state, example/edit provenance, clearing/repeated actions, Unicode, saved-state restoration.
- Compose instrumentation: recording disabled, repeated example/clear, Activity recreation.
- `assembleDebugAndroidTest` checks test compilation only. Actual execution needs an emulator or device.

Before a real release: expand accessibility and large-font/device coverage, test task/process recreation, decide persistence and deletion guarantees, implement lifecycle-safe audio capture, and review permissions/privacy. No release signing or deployment is configured.
