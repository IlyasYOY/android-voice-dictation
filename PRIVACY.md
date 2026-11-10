# Privacy and data flow

Applies to the Android app in main as reviewed on 2026-11-10 (0.19.2). This describes the shipped code, not a promise about a phone's OS, a document provider, another app or OpenAI's account-specific policies. No account or developer-operated transcription server is required by this project. You supply your own OpenAI API key.

## What stays on your device

| Data | Storage and lifetime |
| --- | --- |
| Audio and original/processed text | App-private, no-backup recording repository. Record starts locally. Saved recordings and text remain until explicit deletion; the default count limit is 100 and does not automatically erase old items. Audio and text are **not separately encrypted by the app**; Android app isolation/device storage protection still apply. |
| API key | AES-GCM ciphertext plus a random IV in private preferences; encryption key in Android Keystore. Forget key removes the payload and Keystore alias, not your saved history. This does not protect against every rooted/compromised device or process inspection. |
| Vocabulary | App-private no-backup vocabulary repository. Terms persist until you change/delete them. |
| Text editing settings | Model, enablement and prompt in a private settings file. A prompt can contain personal data; it is not an API key and is not encrypted by this settings store. |
| Floating-control position and preferences | Private local preferences. |
| Diagnostics | A bounded app-private no-backup event store; details below. |

The manifest disables Android backup, and both backup rule files exclude cloud backup/device transfer domains. OS/vendor behavior, rooted backups and copies you export yourself are outside the app's control. Uninstalling/clearing app storage normally removes app-private data; there is no project cloud restore. Deletion is ordinary file deletion, not a secure-erasure guarantee.

## What leaves the device and when

Release composition uses HTTPS `api.openai.com` directly, with the saved key in the Authorization header. The network client disables redirects and automatic retries; history retries are explicit actions. Standard network metadata such as the source IP reaches the provider.

| Action | Request/data |
| --- | --- |
| Stop a recording with a usable key, or explicitly retry saved audio | One multipart POST to `/v1/audio/transcriptions`: recorded M4A audio, model `gpt-transcribe`, output format and any saved vocabulary terms as `keywords[]`. Audio is not continuously streamed. |
| Enabled text post-processing after recognition, or explicitly reprocess a history item | A separate POST to `/v1/responses`: the original transcript, selected model and editing prompt, with `store:false`. No audio, tools or conversation history is attached. This may incur additional API charges. |
| Refresh model list | Explicit authenticated GET `/v1/models`; no dictation or prompt is attached. Opening settings alone does not make this request. |
| Billing button | Opens the official OpenAI billing page in an external browser. The fixed URL contains no API key or dictation. Browser/account cookies are managed by that browser and OpenAI. |

Recording works without a key. If no usable key exists, or recognition fails, the app retains recoverable local data for an explicit retry. A transcription may already have reached the provider when you cancel or a connection fails; cancellation cannot retract a received request or guarantee no charge. The original transcript is saved before optional text editing; a failed second stage does not silently insert the original instead.

`store:false` is not a promise of zero provider retention. Provider data controls vary by endpoint and account; review [OpenAI's current data-controls documentation](https://developers.openai.com/api/docs/guides/your-data) and your account settings before sending sensitive material. API billing/credits and model access belong to the API account; a ChatGPT subscription by itself does not establish this key's available API quota.

The release app has no analytics/advertising/crash-upload SDK or automatic diagnostic upload in its declared runtime composition. Debug-only localhost transports and synthetic credentials are separate test fixtures and are not release entrypoints.

## Other apps, clipboard and the floating control

Accessibility is optional, disclosed before enablement, and Android enforces its service permission. The service can inspect the focused editable node/window and input metadata needed for the control. Around final insertion it may read bounded surrounding text transiently to validate/confirm the write. It does not save a screen/field dump or log another app's package/field text. The Android permission is broad: enable it only if you trust this app and revoke it in Android Accessibility settings when not needed.

On Android 13+, the app may commit text once to the originally captured supported input connection, after revalidating focus, session, caret, composition, lock/keyboard and field policy. It never presses Send, replaces the whole field, retargets a saved history retry or automatically repeats an uncertain write. Unsupported versions/editors use explicit Copy. Password, numeric/payment and other excluded fields remain restricted; this policy cannot prove that every app labels sensitive fields correctly.

Copy writes the selected transcript version to Android's clipboard. Paste in key setup reads the clipboard only after your action. Clipboard text can be exposed to the OS, keyboard, other eligible apps, previews or clipboard managers; the app cannot recall copies from them. Screenshots are allowed, so screenshots and screen sharing may expose displayed content.

During floating recording and processing, the visible overlay requests that the screen stay on. It does not bypass manual power-off or the lock screen and does not guarantee capture/upload continuation while locked.

## Diagnostics and reports

Diagnostics accept only fixed area/action/code enums, a timestamp and an optional numeric HTTP status. They do not record audio, original/processed text, prompts, vocabulary, keys, headers, response bodies, URLs, exception messages, stack traces, clipboard contents, device identifiers or other-app editor data. The store is limited to 7 days / 1 MiB / 16 segments and is pruned while running and before export; an inactive app does not run a deletion task. Queued events can be lost on abrupt termination.

Settings → **Save logs ZIP** writes `events.jsonl`, `manifest.json` and `README.txt` to a location you choose with Android's document picker. The manifest includes numeric app version and Android API plus queue/storage counters. Saving does not upload/share it. The destination provider may sync it according to its own settings. Exported copies survive internal retention and are your responsibility; review them and the surrounding report before sharing. Do not attach personal audio, private document screenshots or API keys to a public issue.

See [diagnostic implementation details](docs/diagnostics.md) and [privacy evidence](docs/privacy-evidence.md). Questions can be raised without personal data in a repository issue. For a suspected vulnerability, follow the repository's security-reporting guidance.
