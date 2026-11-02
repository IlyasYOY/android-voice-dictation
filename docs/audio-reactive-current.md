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

## Проверка на телефоне (названия элементов UI на английском)

1. Установите тестовый APK как обновление. Проверьте старые записи в **History** и их воспроизведение. При ошибке подписи не удаляйте приложение ради установки: это удалит данные.
2. Включите авиарежим и, даже без API key, нажмите **Record**. Запись должна начаться сразу. Произнесите тихую фразу, затем громкую: сила движения должна увеличиться. Замолчите: реакция должна плавно затихнуть, без имитации продолжающейся речи.
3. Протяните низкий гласный звук или гудение, затем более высокое шипение «с»: движение должно смещаться от низа к верхней части контура, с плавным переходом. Достаточно громкий окружающий шум тоже вызывает реакцию; приложение не определяет личность говорящего.
4. Нажмите **Stop**: реакция на звук должна прекратиться сразу. Без ключа или сети полная запись остаётся в **History** для явных **Retry**, **Play**, **Copy**, **Delete**. Прослушайте начало и конец. Проверьте короткую (0,2 секунды), обычную и длинную записи: не должно быть пропущенного конца, перегрузки или изменения скорости/тона.
5. Нажмите **Record**, затем **Cancel**: отменённая запись не должна остаться в истории. Сразу начните следующую. Во время другой записи сверните или поверните Activity: файл должен сохраниться локально без отправки.
6. Включите существующую плавающую кнопку. Откройте клавиатуру в поддерживаемом текстовом поле и повторите тихую/громкую речь, гудение/шипение и тишину. Маленькая клякса должна реагировать так же, с теми же цветами состояний. **Stop** и **Cancel** должны сохранить прежнее поведение вставки/отмены; скрытие клавиатуры и блокировка телефона должны выключить захват.
7. Отключите системные анимации через **Remove animations** или Animator duration scale → **Animation off**. Цвет состояния остаётся виден, но контур статичен даже при речи. Проверьте действия TalkBack **Record**, **Stop**, **Cancel**.
8. Перезапустите приложение после завершённой записи: её звук и текст должны сохраниться. Завершение процесса посреди записи проверяйте только на ненужных тестовых данных: строка истории должна остаться как прерванная, но незавершённый M4A может не воспроизводиться.

Android 28 software-emulator tests can validate codec/container/lifecycle behavior, not physical microphone frequency response, speaker identity, vendor encoder performance, or subjective animation quality. Device listening/speaking acceptance remains a merge gate. No video is needed.
