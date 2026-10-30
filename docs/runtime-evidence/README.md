# Real Android runtime evidence

Captured from the running app on Android 9 / API 28 x86_64 in the published cloud workspace on 2026-10-30. Recording uses real MediaRecorder and the app sends real multipart HTTP bytes to a local MockWebServer. **Only transcription responses are synthetic; no live OpenAI call or paid recognition was performed.** The test-only in-process setup supplies a synthetic key, labels the UI, and clears FLAG_SECURE for capture. Normal app screenshots are protected.

| Evidence | State |
| --- | --- |
| [01-idle.png](01-idle.png) | Idle, key required |
| [02-recording.png](02-recording.png) | Real recorder, timer, Stop and Cancel |
| [03-transcribing.png](03-transcribing.png) | Upload to local test server |
| [04-result.png](04-result.png) | Editable synthetic response and Copy/Clear |
| [05-error-retry.png](05-error-retry.png) | API failure and explicit retry |
| [06-permission-denied.png](06-permission-denied.png) | System microphone denial recovery |

[Raw screen recording](dictation-prototype-demo.mp4): about 76 seconds, H.264 320×640, no audio. It includes a cold emulator launch/blank loading period before the actual scenario. The separate scenario completed `OK (1 test)`. The MP4 was decoded with ffmpeg and inspected at representative timestamps.

[Machine-readable reports and logs](verification-reports.zip): 17 JVM tests, 6 executed Android tests, strict lint, build and runtime logs. Both full Android runs passed 6/6. Code under test: `cfd35138b15999f686c831da2cb8cb0642b3619a`; subsequent delivery changes only add this evidence and CI artifact paths. See [verification](../verification.md) for limits, including no API 36 runtime coverage and no floating overlay.

The signed debug APK is delivered as the private Actions `app-debug` artifact, not stored in source. A runner's debug signing key/checksum can differ from the locally installed test APK; both use the same production source. No user recording, real API key or signing private key is present here.

Native Library delivery was attempted through the current skill's bundled upload helper. Its authenticated tools/list request returned HTTP 401 before preparation or file creation. No Library IDs were created; no credentials were inspected or changed. Evidence is therefore retained in this private PR and included in the `android-reports` CI artifact.
