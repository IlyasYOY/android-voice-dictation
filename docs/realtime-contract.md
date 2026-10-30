# Verified OpenAI contract (2026-10-30)

Primary sources checked independently before implementation:

- [Realtime transcription guide](https://developers.openai.com/api/docs/guides/realtime-transcription): dedicated transcription connection, session configuration, PCM audio, incremental/final events and item ordering.
- [GPT Live Transcribe model](https://developers.openai.com/api/docs/models/gpt-live-transcribe): the current low-latency transcription model; distinct from the audio-conversation models.
- [WebSocket authentication](https://developers.openai.com/api/docs/guides/voice-websockets?api=realtime): bearer header. The conversation-model URL in its generic example is replaced by the transcription guide’s URL.
- [Realtime session reference](https://developers.openai.com/api/reference/resources/realtime/subresources/client_secrets): transcription session/audio schema. This app does not create client secrets or a backend.
- [Realtime server event schemas](https://developers.openai.com/api/reference/resources/realtime/server-events): committed item IDs/predecessors and transcription events.
- [File transcription](https://developers.openai.com/api/docs/guides/speech-to-text): recommended `gpt-transcribe`, supported M4A input and 25 MB upload limit.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): encryption key management.

Live implementation uses `wss://api.openai.com/v1/realtime?intent=transcription`, no conversation `model` query. `session.update` selects transcription type, `gpt-live-transcribe`, `audio/pcm` at 24000 Hz, and null server turn detection. PCM16 little-endian mono chunks go through `input_audio_buffer.append`; client speech/silence detection commits turns. The app waits for `session.updated` before opening the microphone. Stop commits remaining audio and waits for matching finals.

The reducer matches delta/completed events by `item_id`, suppresses duplicate event IDs and uses committed predecessor links to order turns. Finals replace their partials. The production transport pings every 15 seconds; an unanswered ping fails the connection within the next interval, even during silence. Connect and final-drain deadlines are separate. Disconnect does not replay audio or reconnect automatically; received text stays visible, and unfinished text remains marked preliminary. Starting again opens a fresh session explicitly.

File mode encodes mono AAC at 48 kbps: 30 minutes is approximately 10.8 MB, under the internal 20 MB recorder guard and 24 MB upload validation. No chunk splitting is required at this cap/encoding. Safety stop processes all captured audio and shows a notice without a countdown. Upload timeouts retain the recording for explicit retry. No real OpenAI call, key setup or billing verification has been performed; deterministic HTTP/WebSocket fixtures exercise the protocol, not model quality.
