# Audio-reactive Soft Current

The main control and keyboard overlay consume the same `VoiceLevelSource` and render through `CurrentDrawable`/`CurrentGeometry`. A nine-region spectrum deforms the lower contour for bass, middle contour for mids, and upper contour for treble. Adjacent regions interpolate with smoothstep; they are not visible equalizer bars. Overall RMS controls strength, with small deterministic asymmetric phase offsets subordinate to the sound. State color remains independent. This measures actual microphone sound, including sufficiently loud ambient noise; it does not identify a particular speaker.

## Single recording path

`AndroidAudioRecorder` now uses one `AudioRecord` MIC source, PCM16 mono at 24 kHz, one AAC-LC `MediaCodec`, and `MediaMuxer` M4A output at the unchanged 48 kbps. Every ephemeral PCM buffer goes to the encoder first, then to the local spectrum analyzer. There is no second microphone, provider call, permission, raw file, sample logging, or stored spectral history. Recording still starts before credentials/network readiness; playback is interrupted before capture, and shared history allocates/reserves capacity before microphone start.

The recording worker performs nonblocking reads and bounded codec polling. Stop signals the worker, silences the meter immediately, stops/releases the microphone, queues EOS, drains encoded output, then finalizes MP4. Cancel skips deliberate finalization and discards only after resource cleanup. The existing synchronous recorder port waits at most two seconds; no arbitrary driver thread is killed. A stalled worker keeps the process microphone lease and its history reservation until actual cleanup. A failed/timed-out Stop transfers its clip to recovery only after cleanup; subsequent lifecycle cancellation cannot delete it. Every safety/error callback still passes through the session generation guard.

Successful Stop retains playable AAC/M4A until explicit Delete. Process death during capture can leave an unfinished MP4, as before; startup preserves its bytes and interrupted metadata but cannot promise playability. No automatic upload/retry is added.

## Signal and motion bounds

- 1024-sample Hann FFT, DC rejection, nine bands: 70–150, 150–260, 260–440, 440–730, 730–1200, 1200–2000, 2000–3300, 3300–5500, 5500–10000 Hz.
- About 23.4 analyses per second. Fixed scratch arrays and no PCM queue. RMS and band energy are normalized over -55 to -10 dBFS, with a low-level gate; ~45 ms attack / 180 ms release.
- Renderer polls an immutable atomic snapshot at at most 30 fps. No per-frame Compose state updates or history reads. A stalled source fades after 100 ms and is fully stale at 300 ms; stopping/cancelling resets immediately.
- Shape deformation stays within the existing touch target. Idle/error/processing never read the meter. System-disabled animations ignore meter deformation and retain static state colors. Detached/hidden views stop scheduling frames. Setup preview remains decorative and cannot allocate recording resources.

## Acceptance on a phone (UI labels in English)

1. Install the test APK as an update. Check existing **History** recordings and playback; do not uninstall to bypass a signature error.
2. In airplane mode with no API key, tap **Record**. Capture should start immediately. Say a quiet sentence, then a louder one; the louder voice should move the contour more strongly. Pause: the response should settle without pretending speech continues.
3. Sustain a low vowel/hum and then a higher hiss or “s”: movement should favor the bottom versus upper contour, with a smooth transition. Ambient noise can also produce a response; this is not speaker identification.
4. Tap **Stop**. The response must stop immediately. Without credentials/network the complete recording remains in **History** for explicit **Retry**, **Play**, **Copy**, or **Delete**. Listen to its beginning and end. Test short (0.2 s), normal and longer captures; no clipping, speed/pitch change or missing tail.
5. Tap **Record**, then **Cancel**; no cancelled audio should remain. Start another recording immediately. During another capture, leave/rotate the Activity: the local clip should be retained without an upload.
6. Enable the existing floating button. In a supported text editor, repeat quiet/loud, hum/hiss and silence with the keyboard open. The same contour behavior and colors should appear at the smaller size. **Stop** and **Cancel** must preserve their existing insertion/cancellation behavior; hiding the keyboard or locking the phone must stop capture.
7. Disable system animations (**Remove animations**, or Animator duration scale **Animation off**). State colors remain visible but the contour stays static, including while speaking. Check TalkBack **Record**, **Stop**, and **Cancel** actions.
8. Restart the app after a stopped capture. Existing audio/text must remain. Test a process kill during capture only on disposable test data: the interrupted row should remain; an unfinished container may be unplayable.

Android 28 software-emulator tests can validate codec/container/lifecycle behavior, not physical microphone frequency response, speaker identity, vendor encoder performance, or subjective animation quality. Device listening/speaking acceptance remains a merge gate. No video is needed.
