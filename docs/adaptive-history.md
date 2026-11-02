# Adaptive main and history

Layout uses the **current inset-safe app window**, including resize/multi-window, not a phone model, screen size or folded flag. At **840 dp** available width, dictation stays left and the existing message history is persistent on the right. Below that boundary the saved-count entrance/swipe opens the same bottom sheet. No second repository, upload flow or history design is introduced. Settings and onboarding occupy the main slot; history remains available beside them.

840 dp follows Android's expanded-width boundary. Both panes must also fit `320 × max(1, fontScale)` dp for dictation and `360 × max(1, fontScale)` dp for history, plus 24 dp spacing. Thus large fonts can deliberately select the compact layout even in a broad window. Without a separating fold, history receives about 46% of the width, bounded by those minima. Both panes scroll independently and respond to height/IME/insets; orientation alone selects nothing.

One focused `androidx.window:window:1.5.1` dependency supplies real `FoldingFeature` information. A separating vertical hinge is the gap if both sides fit; otherwise the app uses the larger unobstructed side with the compact sheet. A horizontal separating fold uses the larger unobstructed top/bottom region. Hinge coordinates are translated from window pixels to the actual safe content bounds; no controls are intentionally placed across a separating fold. If the device supplies no hinge information, ordinary width adaptation still works. No new permission is required.

## State and lifecycle

The main composition slot stays in place through width changes, preserving Settings and scroll. History shares one `HistoryUiState`: list position, expanded message IDs, current-text expansion, menu/confirmation identity and limit dialog. Only identifiers and positions enter saved UI state; transcript and credentials remain in existing session/storage owners. Message content and actions are the same implementation in both presentations.

An Activity-retained `HistoryViewModel` owns the single player and explicit retry jobs. Moving sheet ↔ pane does not restart playback, duplicate HTTP or cancel a submitted retry. Configuration recreation retains this owner and the dictation ViewModel; `MainActivity.onStop` only backgrounds the recording session when actually leaving, not during configuration changes. Actual background, explicit sheet dismissal, deletion/retry, focus loss or microphone start release playback as before. No process-death playback/recording continuation is promised: durable recovery remains the existing explicit workflow.

There is exactly one history presentation. Entering wide removes the modal and its scrim. If the compact sheet was open before widening, it returns open when narrowed; if it was closed, narrowing returns the normal saved-count entrance. Pending confirmed-delete and limit dialogs transfer with their state. Persistent history has a pane accessibility title but no dismiss action; compact history retains Back, drag dismissal and its accessible dismiss action. No visible History/Close heading or swipe instruction is added.

## Verification

Pure policy tests cover 839.99/840 dp, larger font, usable/unusable vertical hinges, tabletop, off-window features and zero-size transitions. Compiled Compose tests cover sheet/pane resizing, one content instance, expanded edited draft, Settings continuity, saved UI restoration, shared scroll and pending deletion. An Activity test recreates during Record and transcription and asserts a single request. These Android tests require a functioning device; compilation alone is not runtime evidence.

Current environment: no connected Android device or KVM; repeated earlier SystemUI/WindowManager failures exhausted the available recovery. This milestone does not claim Android execution, hinge-device validation or new screenshots until an actual run succeeds. No paid provider requests are used by tests.

References: [window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes), [canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [fold-aware Compose](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware), [WindowManager stable release](https://developer.android.com/jetpack/androidx/releases/window).

## Ручная приёмка

1. Установить обновление поверх существующего приложения: история, ключ, лимит и положение floating button сохраняются. Открыть маленькое окно: снизу только количество записей; tap/swipe открывает историю, Back/drag закрывает.
2. Расширить окно до ≥840 dp при обычном шрифте: диктовка слева, история справа постоянно; нижнего входа и scrim нет. Сузить окно: история снова доступна снизу. Повторить в landscape и split-screen; размер устройства сам по себе не должен включать две панели.
3. Раскрыть длинный transcript, отредактировать текущий текст, прокрутить историю; несколько раз сложить/разложить и изменить ширину. Текст/раскрытие/позиция и открытые Settings сохраняются. При шрифте 200% допускается компактный режим ради читаемости.
4. Открыть Delete и изменить ширину до подтверждения: один диалог, Cancel не удаляет, Delete удаляет только выбранную запись. Повторить с лимитом истории и открытой клавиатурой.
5. Включить аудио, изменить ширину и повернуть экран: воспроизведение продолжается там, где аудиофокус ОС позволяет. Pause/смена записи работают. Закрытие компактной истории, Home, Retry/Delete и начало Record освобождают плеер.
6. Начать офлайн Record, изменить ширину/повернуть экран, затем Stop: одна сохранённая запись. Без ключа нет HTTP. При synthetic/local fixture или собственной готовой конфигурации повторить во время transcription: один запрос, без повторной вставки/автоповтора.
7. На устройстве с разделяющим шарниром проверить обычную/книжную/tabletop позиции: кнопки не пересекают шарнир; при тесных половинах одна безопасная область и sheet. Нет обещания проверки конкретной OEM-модели без её фактического запуска.
8. Проверить Play/Pause, раскрытие/Copy, явный Retry, подтверждаемое Delete, заполненный лимит и старые текстовые записи. Нет Live-переключателя, автоудаления или автозагрузки.
