# Floating editor compatibility

Version 0.19.2 keeps one `commitText` attempt on the original Android 13+ accessibility input connection. The focused editor, window, IME session, unchanged collapsed caret, lack of composition and existing sensitive-field policy are revalidated after the asynchronous preflight. Lock, hidden keyboard, cancellation and owner invalidation still prevent writes. Selection replacement, full-field `ACTION_SET_TEXT`, clipboard/paste fallbacks, Send and automatic insertion retries are not used.

Android's surrounding-text read is optional; `null` can mean an unimplemented read protocol, not necessarily an unsupported write. `SurroundingText.offset == -1` explicitly means an unknown absolute offset. Previously either response caused Copy without trying `commitText`. An unavailable read now permits one attempt only if all the independent editor/session/caret gates still pass. Returned malformed, selected or conflicting metadata still rejects the write. An unknown offset uses the IME's absolute caret metadata rather than inventing an offset.

Confirmation requires the same editor, the expected absolute IME caret and the bounded inserted suffix immediately before the returned local caret. A readable absolute offset must agree with the IME caret. Unreadable or inconsistent confirmation remains UNCONFIRMED; retained text stays available and the app never automatically retries a potentially successful write. A read exception before the write still offers Copy. No dictation/field text is added to diagnostics or persisted as editor context.

References: [AccessibilityInputConnection.getSurroundingText](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#getSurroundingText(int,int,int)), [SurroundingText.getOffset](https://developer.android.com/reference/android/view/inputmethod/SurroundingText#getOffset()).

## Validation and limits

`FinalTextInsertionTest` covers normal/unknown offsets, missing reads, malformed/contradictory metadata, styled-buffer suffix positioning, focus/session/caret changes during suspended preflight, selected/composing/sensitive fields, cancellation and one attempt after uncertain writes. `EditorProtocolRuntimeTest` exercises real Android EditText/InputConnection insertion, including multiline text and retained spans; it controls the optional read responses and does not represent the accessibility IPC transport. The cross-UID `CrossAppTest.pEditorProtocolsInsertAtTheCaretOnceWithoutReplacingOrSubmitting` uses the actual product accessibility service with native single/multiline fields and custom read protocols, a separate fixture keyboard and localhost synthetic transcription. Execution results must be reported separately from compilation.

These fixtures are not Google Docs. The cloud AOSP image has no Google Docs application or Play Store; no unofficial APK mirror or personal Google account is used. The change addresses a confirmed protocol incompatibility, but the precise cause in the user's Docs version is not established by fixture results. Native Google Docs, Chrome/contenteditable, OEM keyboards, protected/password/payment fields and Android versions must not be presented as tested unless separately executed.

## Phone acceptance (synthetic text only)

1. Install the signed update over 0.19.1. Enable floating dictation if necessary. Use a disposable document/field and the user's normal keyboard.
2. In native Google Docs, type `Before  after`, place the collapsed cursor between the two spaces, dictate a short synthetic phrase and stop. Expect exactly one phrase at that position and both surrounding parts intact. Repeat at the beginning/end and with multiline speech in a multiline document. Check existing formatting remains.
3. Repeat in a normal message draft, a search field (single line), browser textarea and a rich/contenteditable field. Do not send messages or submit forms. A multiline result in a single-line field intentionally remains available to Copy.
4. During a dictation move to another field, move the cursor, hide the keyboard, cancel or manually lock the screen. Expect no later insertion into the old or new field; saved audio/text remains recoverable.
5. A selected range or active keyboard composition is intentionally not replaced. Password, numeric/payment and other sensitive fields remain excluded.
6. If Docs still fails, export Settings → Save logs ZIP and report Android version, native Docs vs browser, keyboard/version, whether the blob appeared, and whether insertion failed at all positions or only after formatting/cursor changes. Do not include private document text or API keys.
