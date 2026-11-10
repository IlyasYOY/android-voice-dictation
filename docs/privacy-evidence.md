# Privacy implementation evidence

Reviewed against main `19614700da4671eaceaf1c2a6ee4445b24dc20a0`. The top-level [privacy statement](../PRIVACY.md) is a description of these implementation boundaries, not a provider compliance certification.

| Claim | Source and relevant verification |
| --- | --- |
| Encrypted API key; explicit save/forget | `data/ApiKeyStore.kt`, `SerializedApiKeyStore.kt`; key persistence and onboarding tests |
| Local audio/text; explicit deletion/capacity | `data/RecordingRepository.kt`, `AndroidRecordingStorage.kt`, `session/DictationSession.kt`; durable storage, capacity and recovery tests |
| Vocabulary sent only with explicit transcription | `OpenAiTranscriber.kt`, `AndroidVocabularyStorage.kt`; vocabulary/transcriber tests |
| Single request, no redirect/replay | `OpenAiTranscriber.kt`, `TextProcessing.kt`, `SingleAttemptRequestBody.kt`, `TextModels.kt`; transport tests use MockWebServer, never a personal key |
| Separate text request, original retained, store false | `TextProcessing.kt`, `TextProcessingPreferences.kt`, `session/DictationSession.kt`; post-processing and history tests |
| Accessibility scope and one insertion | `floating/FloatingDictationService.kt`, `EditorInput.kt`, `EditorPolicy.kt`, `FinalTextInsertion.kt`, `res/xml/floating_accessibility.xml`; policy/insertion tests. API 28 native-input tests do not prove API 33 accessibility IPC compatibility. |
| Manual clipboard and external billing | `ui/HistoryViewModel.kt`, `onboarding/SetupPlatform.kt`, `MainActivity.kt`, floating setup/service and billing navigation implementation |
| Fixed diagnostic vocabulary and bounds | `diagnostics/DiagnosticEvent.kt`, `DiagnosticStore.kt`, `Diagnostics.kt`, `DiagnosticExport.kt`; DiagnosticStore/Queue/runtime tests |
| Backup exclusions | `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |
| No release test endpoint | `src/debug` and `src/release` variants of `floating/DebugFloatingConfiguration.kt` plus release manifest |

Paths without a prefix are beneath `app/src/main/java/io/github/ilyasyoy/voicedictation/`. Resource/manifest paths are beneath `app/src/main/`. Test existence is not a claim that every runtime test ran in this preparation task; see each PR's validation results.

Release runtime dependencies are AndroidX/Compose, Kotlin/coroutines, OkHttp/Okio and their runtime dependencies. This statement does not cover firmware, Play services, other apps, provider servers, development tools or an arbitrary modified build. No provider request was made to validate privacy claims.
