# Shared blob controls

The main screen now teaches the existing keyboard bubble's interaction. This changes presentation and gesture routing only; session, recorder, transcription, recovery and history code are unchanged.

| State | Tap | Hold |
| --- | --- | --- |
| Ready, result or error | Existing Record action, subject to capacity/permission rules | No Cancel controls |
| Recording | Existing Stop action; closes any open controls | Toggle Recording controls with Cancel |
| Processing, including a pending key read | Close any open controls; no new recording/upload | Toggle the same Cancel controls |

Holding only opens the controls. Cancel remains a separate explicit action, using the existing cancellation callback. The existing main-screen Back/background behavior is preserved. There is no new cross, popup or cancellation gesture. Panels close on session completion, error, cancellation and before a fresh recording. The overlay's behavior is unchanged.

## Discoverability and accessibility

While recording, the existing caption is **Tap to stop · Hold for controls**. Processing uses **Hold for recording controls**, the same wording as the overlay. The controls reuse **Recording controls** and **Cancel**. There is no extra idle onboarding or permanent Cancel button.

Record/Stop/Processing descriptions remain. The active control has a labeled long-click action and an explicit Cancel accessibility action, matching the overlay. The decorative native drawable remains inaccessible and non-clickable on its own. The main caption, status/timer, controls and history entrance remain scrollable; large fonts must not hide the cancellation target.

## Owner checks

1. Update the signed APK over the current app without uninstalling. In the main screen, tap the gray blob to start recording.
2. Read the hint, then hold the purple blob: Recording controls and Cancel should appear. Recording must continue. Release must not trigger Stop.
3. Hold again to close the panel, then open it again. Tap the blob briefly: one Stop should start processing and close the panel.
4. During processing, a tap must not start another recording. Hold to open the same controls; choose Cancel explicitly. Existing received text/history must remain as before.
5. Start another recording: Cancel must stay hidden until a hold. Check success, error, Back/background handling and a full history using the existing flows.
6. Repeat on the floating keyboard blob. Check the active hint and Cancel action with TalkBack, and the main screen at large font/compact size in both themes. Dismissing or opening controls must not create extra uploads.

`BlobControlsRuntimeTest` checks real Compose pointer and accessibility gestures, the unchanged session callback pipeline with a synthetic recorder/deferred response, late cancelled responses, repeated recordings, capacity and compact/large-font layouts. Screenshots contain synthetic states in the real Android UI. They do not establish microphone recognition quality or phone-specific behavior. No paid API calls or videos are part of validation.
