# Floating dictation contract — prototype 0.3

## User control and permissions

The optional feature is off by default. Its own disclosure must be accepted, then the user enables the service in Android Accessibility settings. Separate buttons request microphone and recording notification access. Permission callbacks and service binding never start audio. The service can disable itself from the setup screen. No SYSTEM_ALERT_WINDOW, replacement keyboard, notification listener, boot receiver, gesture injection or accessibility screenshot capability is requested.

The service receives only window changes, window-state changes and focused-view changes. It retrieves interactive window metadata and a focused editor. It never reads AccessibilityEvent.text, node.text, screen screenshots, hints, initial editor text or input extras, and does not retain nodes. Android still grants broader window-content capability; the disclosure says this explicitly. Package, window/view identity, input classification, caret/composition positions and session generation are held in memory only. Nothing from other apps is logged, saved or sent to a server.

## Keyboard independence

TYPE_INPUT_METHOD windows identify an on-screen IME independently of its package. A non-focusable TYPE_ACCESSIBILITY_OVERLAY is attached to the system-bound accessibility service. It does not take input focus. The icon can be dragged; touching it explicitly starts live dictation with the existing streaming transport. During capture only the animated control is shown; received text remains in the owned session and is available through intentional recovery after interruption. There is no automatic start on focus/keyboard events.

No claim of universal keyboard/editor support is made: a hardware keyboard with no visible IME, unexposed/custom editors, floating or OEM windows and privacy-restricted fields may offer no bubble or require Copy. The separate fixture APK contains a second test-only IME; the product does not ship or require it.

## Microphone lifecycle

On the icon tap, the already system-bound AccessibilityService is promoted to a typed microphone foreground service with an ongoing Stop/Cancel notification. A one-shot pending origin token gates START; a null/replayed/external start cannot trigger recording. The service returns START_NOT_STICKY. Microphone and notification grants are checked before starting and while active. Modern Android can reject the start: this is surfaced and no hidden retry occurs.

Android 16 AOSP binds accessibility services with BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS and BIND_INCLUDE_CAPABILITIES. ActiveServices permits while-in-use foreground starts for processes with the corresponding bound-service permission. This is an implementation-supported strategy rather than a promise about all OEMs. Runtime verification must use a separate fixture UID, not product instrumentation, and wait beyond the recent visible-Activity grace period.

Overlay text rendering reuses the most recently validated eligibility; it does not synchronously reread an editor on every transcript delta. Focus/window events can advance an already scheduled check and never postpone it, preventing continuous overlay events from starving focus-loss cleanup. Start and insertion still perform fresh metadata/keyboard checks.

Cancel, keyboard dismissal, screen off/keyguard, loss of access, focused editor/caret change, orientation, interrupt/unbind and stream failure stop microphone/network work. Cancel also closes the owned DictationSession and observer, so a pending key load cannot start audio later. No restart/reconnect or audio replay occurs automatically. Received dictation may remain for explicit Copy while the panel remains valid; keyboard dismissal hides it while pending recovery remains in process memory.

## Insertion safety

Android 13/API 33 added AccessibilityService InputMethod and AccessibilityInputConnection. The service captures the original connection plus package/window/view/session and caret metadata when recording is explicitly started. On older Android versions only Copy is offered. The current keyboard is never replaced.

Only final, non-empty text is considered. Both origin/current fields must be ordinary compatible text editors, same session and same collapsed caret, with no selected or composing range. Newlines require a multiline editor. Long output above 10,000 UTF-16 units remains available via Copy. Password, numeric/payment, no-personalized-learning, sensitive-classified and recognized security fields/packages are excluded conservatively; incorrectly labelled third-party fields cannot be guaranteed detectable.

Immediately before writing, zero-length getSurroundingText(0,0,0) checks only absolute caret metadata; unsupported or changed fields receive no write. The captured original connection receives exactly one commitText(text,1,null). No ACTION_SET_TEXT, paste, key injection, performEditorAction, Enter, Send or submit is used. Existing sides of the caret are left to the editor's native insertion semantics. A bounded local suffix check (at most the last 256 characters expected from this dictation) and caret check attempt to confirm the write; this transient value is not logged, retained or transmitted. If the editor fails or cannot confirm, a clear warning asks the user to check before Copy; there is no retry/rollback that could duplicate text. Focus changes never retarget insertion to another field.

The accessibility InputMethod.onFinishInput override deliberately does not call its default implementation, which could clear another IME's composition. Selection/composition fields use Copy instead.

## Primary sources, checked 2026-10-30

- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) — interactive windows, overlay and API 33 input method.
- [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo) — TYPE_INPUT_METHOD.
- [InputMethod](https://developer.android.com/reference/android/accessibilityservice/InputMethod) — editor callbacks and default finish behaviour.
- [AccessibilityInputConnection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection) — commitText, null/unsupported surrounding text and blocking IPC.
- [Foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) — background and while-in-use microphone restrictions.
- [Android 16 AccessibilityServiceConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java) — system bind flags.
- [Android 16 ActiveServices](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ActiveServices.java) — shouldAllowFgsWhileInUsePermissionLocked and bound-service exemption.

Live OpenAI authentication, recognition, billing and quality remain unverified; all automated network scenarios use a clearly labelled deterministic local server. No real API key is entered by the agent.

## User screenshots

The app does not set `FLAG_SECURE` on its Activity or accessibility overlay. Users can capture the ordinary app screen, compact recording bubble and result panel; transcripts may therefore appear in screenshots, screen recordings and recent-app previews. The API-key field remains permanently password-masked, with no reveal control. This policy affects only our windows and does not change other apps' protected windows, editor exclusions, OS capture restrictions or Accessibility permissions.

## Soft Current controls

During an active session the overlay is a compact wave circle without transcript or a persistent button cluster. Tap stops recording. Holding opens an explicit Cancel control inside the same non-focusable overlay; opening controls must not stop audio or retarget the editor. Cancel also remains available as an accessibility action and in the recording notification. Its existing data policy is unchanged. The shape itself flows continuously, with muted connecting, faster purple capture and gently settling processing; there is no nested spinner, rim or separate recording dot. Routine progress toasts and automatic final reports are absent. Only confirmed insertion returns to idle. Copy fallback, unconfirmed insertion, errors and cancellation retain text/reason behind a compact attention state; a tap opens recovery. Dismiss collapses recovery without clearing it; Discard text explicitly clears the pending result; the next tap may start a new session. No automatic clipboard write or reconnect is performed. Position remains transient, and floating mode remains Live.

Pending floating recovery is process-owned memory containing text/reason only, with no editor connection, audio, credential or durable history. Keyboard hide, lock, Setup, configuration changes and service rebind close session resources and dismiss the panel while preserving that pending recovery. Only confirmed insertion or explicit Discard clears it; process death still loses it. A later eligible field can expose manual Copy, never replay or automatic insertion. Recovery is measured to the real available height above the keyboard and scrolls long text/actions; buttons wrap at large font sizes.
