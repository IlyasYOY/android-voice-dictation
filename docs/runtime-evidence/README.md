# Real Android 0.2.0 evidence

Captured from the running Activity on Android 9 / API 28 x86_64 in the published cloud workspace. Microphone capture, HTTP/WebSocket transport, clipboard and Android Keystore are real. **Transcription responses are synthetic local-server responses; no live OpenAI or paid call was made.** The pinned test banner and masked fixture key identify synthetic-data capture; production FLAG_SECURE remains enabled.

| Screenshot | Scenario |
| --- | --- |
| [01-idle.png](01-idle.png) | Two modes, no recording before a key |
| [02-recording.png](02-recording.png) | Real file recording, elapsed-only timer, Stop/Cancel |
| [03-transcribing.png](03-transcribing.png) | Actual multipart request to local server |
| [04-result.png](04-result.png) | Editable file result and copy |
| [05-error-retry.png](05-error-retry.png) | Explicit file retry |
| [06-permission-denied.png](06-permission-denied.png) | System microphone denial recovery |
| [07-live-partial.png](07-live-partial.png) | Actual AudioRecord/WebSocket, preliminary words before Stop |
| [08-live-finishing.png](08-live-finishing.png) | Microphone off, awaiting final segments |
| [09-live-final.png](09-live-final.png) | Final replaces partial, without duplicates |
| [10-live-disconnected.png](10-live-disconnected.png) | Connection failure, received words retained, explicit reconnect |
| [11-key-saved.png](11-key-saved.png) | Masked synthetic key, secure Save status and Forget |

[Short live clip](dictation-prototype-demo.mp4): 28-second excerpt, seconds 26–54 of [full raw screen recording](dictation-v2-full-demo.mp4), no audio. The excerpt removes emulator startup/waits; it contains only real runtime frames. The full recording includes both live and key-storage UI tests, completed `OK (2 tests)`. MP4 decoding and representative frame inspection passed.

[Machine reports and logs](verification-reports.zip): 39 JVM tests, 11 executed Android tests, three forced-process-restart key phases, strict lint/build logs and video test result. See [complete verification](../verification.md) and [official API contract sources](../realtime-contract.md).

The APK is delivered natively through Library and as the private Actions `app-debug` artifact; it is not committed in source. Runner debug signing/checksum may differ from the locally tested Library APK. No real key, signing private key or user audio is included here. The thirty-minute safety boundary is tested virtually, not by waiting thirty real minutes. OpenAI recognition quality/billing remain unverified; floating overlay remains separate.
