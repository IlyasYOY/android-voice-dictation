# Soft Current visual candidate

The selected [Soft Current concept](https://chatgpt.com/space/page_7c4b24e62d288191807f1dd26cd11496) guides this visual PR. The exact reference was read through the authorized Page file API, materialized in the cloud executor and inspected (SHA-256 `64c46b904f7cea13eb37c620114b301740378799b20e884f5742813091901823`). Generated design boards, runtime images, videos and machine reports stay outside Git.

## Included

A lavender/off-white and deep-plum palette, rounded typography, layered organic wave control, quiet status hierarchy, and matching compact floating recording control. API-key configuration lives in accessible Settings, while the existing in-app modes have a Live/Record segment with a moving translucent selection on the main screen and in Settings; the existing floating-access setup keeps its permission behavior and receives the same visual treatment. Existing results/edit/copy/retry actions remain real and accessible. Save/Forget failures have a pinned, announced Settings error so a scrolled or keyboard-open form cannot hide them.

The active floating control stays compact with no transcript or button cluster. Tap stops; a labelled hold-for-controls action opens a small non-focusable Cancel menu in the same overlay, and accessibility/recording-notification Cancel actions remain available. Cancellation keeps its existing semantics; there is no new destructive hold-to-cancel policy. Routine progress toasts/tooltips and automatic final panels are removed. Confirmed insertion settles the same animated mark to idle. Unconfirmed writes, Copy fallbacks, errors and cancellation stay compact; tap opens the retained text/reason and explicit Copy/setup actions. Dismiss collapses the panel without dropping text; starting over is labelled Discard and start again. No automatic Copy, retry or submit is introduced.

## Acceptance corrections (0.4.1)

The main screen respects explicit safe drawing insets even when the Scaffold top bar has no content. Its scrollable content and wave size adapt to available width/height rather than scaling all text globally. Compact viewport and large-font Android regressions cover navigation, mode selection and wave bounds. Decorative title/taglines, central Record label and confirmed-segment/result headings are removed; the control keeps its accessible Record/Stop semantics. Live cancellation remains in Recording controls and Back rather than a duplicate keep-text button. Record-mode discard/retry handling is unchanged.

Both surfaces draw the same continuously evolving vector path, using a shared drawable frame clock, smoothly blended velocity/pigment and no separate spinner/rim/dot. Idle flows slowly, connecting remains muted with microphone off, real capture flows faster in purple, and processing settles separately. This is state feedback, not a voice-amplitude meter. Motion stops when detached/hidden and respects the system animation-disabled preference. The native motion smoke keeps animation enabled separately from Compose idling tests.

The eight user feedback screenshot transfers failed in this executor; a separate authorized source-review worker inspected all eight originals. They confirm the nested connecting indicators, redundant labels, routine floating toasts and large automatic unconfirmed-insertion panel. No definite hard viewport overflow is visible in the supplied stills, so the independently identified safe-area defect is not claimed as the proven cause of the Samsung report. Compact/large-text regressions and device acceptance remain necessary.

## Deferred

History, offline buffering/recognition, durable bubble position, changing floating/default modes, model or retention settings, automatic clipboard/key setup, connection validation, new onboarding and new cancellation policies. No mock history or dead settings are shown. Audio/network/storage/session ownership and permissions are preserved, as are masked keys, screenshots, explicit start/retry and no-auto-submit guarantees.

This is an acceptance candidate. No merge until the user accepts the APK. API 28 runtime evidence does not establish Samsung One UI behavior or modern native insertion; report those verification limits explicitly.
