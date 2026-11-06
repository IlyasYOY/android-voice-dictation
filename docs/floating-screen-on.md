# Floating dictation screen lifetime

The keyboard bubble requests that Android keep the screen on during an active recording and the following transcription. The request remains continuous across Stop and any pending key lookup. Completion, cancellation, error, hiding the overlay, losing the eligible editor or closing the service releases it. Merely showing the idle bubble does not keep the screen on.

`FloatingCapture` owns the request, independently of the microphone foreground notification. `FloatingWindow` applies it to its attached accessibility-overlay root using `View.keepScreenOn`. The view hierarchy propagates this as `FLAG_KEEP_SCREEN_ON`; detaching or hiding the root also releases the framework's request. The service clears the request before recorder/recovery cleanup. A closed capture ignores late results, including after a subsequent recording starts.

This leaves the device's timeout, brightness, permissions and power-button behavior unchanged. Keeping a lit display consumes battery for the duration of dictation and processing; there is no extra CPU wake lock. A stalled transcription can keep the screen on until cancellation or the existing request timeout. Retry from saved history and dictation in the main Activity are outside this change.

The standard [Android screen-on guide](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on) describes Activity windows. This implementation uses the existing visible accessibility window and the [window flag contract](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON), with a dedicated runtime test for that actual window type. OEM behavior still requires testing on the target phone.

## Manual power lock

The power button remains authoritative. The existing screen-off receiver closes the floating session and removes its window. Recovery attempts to retain a local clip through the existing recorder path; this change neither modifies that path nor promises complete audio or uninterrupted processing after a forced lock. Stronger crash/lock recovery is a separate storage task.

## Owner acceptance

Install the signed candidate over the existing app without uninstalling or clearing data. Use a harmless editor with the keyboard open and a short normal screen timeout, preferably while unplugged or with developer charging stay-awake disabled.

1. Leave the idle bubble untouched: the screen should time out normally.
2. Start dictation and do not touch the screen for longer than its normal timeout: recording should remain active and the display lit.
3. Tap Stop. During an intentionally slow transcription, leave the phone untouched for longer than its timeout: the display should remain lit, without a dark interval at the transition.
4. After the result, leave it untouched: normal automatic screen-off should return.
5. Repeat twice, then try cancellation during recording and processing, and a network error. Normal timeout must return in each case.
6. While active, hide the keyboard, leave the editor, disable floating dictation or close its accessibility service. The screen request must end.
7. Press the physical power button during recording and again during processing. It must turn the screen off; do not expect the operation to continue while locked. After unlocking, inspect any retained recording before starting again.

Screenshots can show the UI states but cannot demonstrate elapsed timeout behavior. Automated runtime evidence and the owner checks above cover that behavior; no videos are required.
