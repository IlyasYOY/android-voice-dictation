# Custom vocabulary (VD018)

Selected design A: `History` / `Words` segmented tabs live only inside the existing history panel. On a compact window this is the same bottom sheet; on a wide window it is the persistent right pane. There is no new main-screen entry. Words has an always-visible input and Add above a scrolling list. Each term offers Edit/Delete in its overflow; editing stays inline. Large fonts stack the input/action to keep both usable. Switching to Words stops history playback; the history implementation and actions are otherwise unchanged. Onboarding still occupies one pane.

Panel selection, unfinished add/edit text, caret and both scroll positions are held above the compact/wide branch and restored across configuration changes. Text is saved in Activity instance state, never credentials. Closed sheets clear focus; a relocated input requests its previous focus. The keyboard reduces the safe viewport and the list scrolls underneath the input.

## Data and requests

`VocabularyRepository` owns `noBackupFilesDir/vocabulary-v1/words.properties`, a versioned ledger independent of history and the encrypted key. Upgrade from versions without a dictionary starts empty and touches neither store. It uses the existing sync/staging/atomic-rename commit boundary. Invalid, unreadable or future schemas are blocked from editing/upload rather than overwritten. A failed commit rereads disk, including the case where rename succeeded before directory sync failed. Reload is explicit; failed input stays in the editor. A leftover staging file cannot replace the last commit.

Terms are trimmed; original spelling, Unicode and multiword phrases are retained. Duplicate comparison uses NFC normalization and locale-independent lowercase. Empty strings, control/line separators and `<`/`>` are rejected. The dictionary has a **local** 64 KiB UTF-8 text budget to bound storage/request size; exceeding it is an explicit error and never truncates words. This is not presented as an OpenAI token/count limit.

The official [transcription guide](https://developers.openai.com/api/docs/guides/transcription#improve-transcription-quality), [multipart example](https://developers.openai.com/api/docs/guides/speech-to-text#improve-transcription-quality) and [request reference](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create) were checked on 2026-11-02. `gpt-transcribe` accepts literal `keywords` hints; multipart sends one `keywords[]` field per term, with no added prompt/language setting. Current documentation does not state a numerical keyword count/length limit. Each keyword must be one line without `<`, `>`, CR or LF. The UI additionally rejects control/Unicode line-separator characters.

The shared application composition root supplies the same repository to main/overlay Record and retained-history Retry. Each explicit request snapshots the current committed list once; edits cannot alter an in-flight request, and the next Retry reads the new dictionary. An empty dictionary leaves the prior multipart fields unchanged. Dictionary read failure is a local-storage error, retains the audio for explicit retry and sends no request. CRUD, reload and startup do not cause HTTP, microphone use, learning, text replacement or commands. No paid API call is used in tests.

Words are sent with audio as recognition hints, not guaranteed output or model training. They remain local until an explicit transcription. No content/key logging is added.

## Ручная приёмка (English UI labels)

1. Открыть существующую History снизу, выбрать Words. На главной нет новой кнопки. На Fold вкладки находятся в правой панели; onboarding остаётся одноколоночным.
2. Добавить `Neovim`, `Taskwarrior`, `Ильяс` и фразу. Проверить сохранение после перезапуска, изменение через Edit word и удаление через Delete word. Дубликат с другим регистром и перенос строки должны дать понятную ошибку без потери ввода.
3. Оставить незаконченное слово, открыть клавиатуру, повернуть/разложить/сжать окно. Вкладка, текст и позиция ввода сохраняются; Add доступна, список прокручивается. Повторить в тёмной/светлой теме и с крупным шрифтом.
4. Вернуться в History, проверить Play/Pause, раскрытие текста, Copy, Retry, Delete recording и лимит истории. Переход в Words останавливает воспроизведение.
5. Сделать запись без сети; добавить специфичный термин в Words; явно нажать Retry upload. Проверить ту же запись в приложении и оверлее над клавиатурой. Изменение слов само по себе не отправляет запрос. Распознавание термина не гарантируется.
6. Проверить прежние адаптивность, тёмный фон, голосовую реакцию кляксы и непрерывность начала/конца/длинной записи на Samsung. Headless rendering не доказывает работу системной IME или микрофона физического Fold.
