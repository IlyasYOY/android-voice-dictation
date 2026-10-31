# Soft Current visual candidate

The selected [Soft Current concept](https://chatgpt.com/space/page_7c4b24e62d288191807f1dd26cd11496) guides this visual PR. The exact reference was read through the authorized Page file API, materialized in the cloud executor and inspected (SHA-256 `64c46b904f7cea13eb37c620114b301740378799b20e884f5742813091901823`). Generated design boards, runtime images, videos and machine reports stay outside Git.

## Included

A lavender/off-white and deep-plum palette, rounded typography, layered organic wave control, quiet status hierarchy, and matching compact floating recording control. API-key configuration lives in accessible Settings, while the existing in-app modes have a Live/Record segment with a moving translucent selection on the main screen and in Settings; the existing floating-access setup keeps its permission behavior and receives the same visual treatment. Existing results/edit/copy/retry actions remain real and accessible. Save/Forget failures have a pinned, announced Settings error so a scrolled or keyboard-open form cannot hide them.

The active floating control stays compact with no transcript or button cluster. Tap stops; holding opens Cancel controls, with accessibility and recording-notification Cancel actions also available. Routine progress toasts/tooltips and automatic final panels are removed. No automatic Copy, retry or submit is introduced.

## Acceptance corrections (0.4.1)

The main screen respects explicit safe drawing insets even when the Scaffold top bar has no content. Its scrollable content and wave size adapt to available width/height rather than scaling all text globally. Compact viewport and large-font Android regressions cover navigation, mode selection and wave bounds. Decorative title/taglines, central Record label and confirmed-segment/result headings are removed; the control keeps its accessible Record/Stop semantics. Live cancellation remains in Recording controls and Back rather than a duplicate keep-text button. Record-mode discard/retry handling is unchanged.

Both surfaces draw the same continuously evolving vector path, using a shared drawable frame clock, smoothly blended velocity/pigment and no separate spinner/rim/dot. Idle flows slowly, connecting remains muted with microphone off, real capture flows faster in purple, and processing settles separately. This is state feedback, not a voice-amplitude meter. Motion stops when detached/hidden and respects the system animation-disabled preference. The native motion smoke keeps animation enabled separately from Compose idling tests.

The eight user feedback screenshot transfers failed in this executor; a separate authorized source-review worker inspected all eight originals. They confirm the nested connecting indicators, redundant labels, routine floating toasts and large automatic unconfirmed-insertion panel. No definite hard viewport overflow is visible in the supplied stills, so the independently identified safe-area defect is not claimed as the proven cause of the Samsung report. Compact/large-text regressions and device acceptance remain necessary.

## Deferred

History, offline buffering/recognition, durable bubble position, model or retention settings, automatic clipboard/key setup, connection validation, new onboarding and new cancellation policies. No mock history or dead settings are shown. Audio/network/storage/session ownership and permissions are preserved, as are masked keys, screenshots, explicit start/retry and no-auto-submit guarantees.

This is an acceptance candidate. No merge until the user accepts the APK. API 28 runtime evidence does not establish Samsung One UI behavior or modern native insertion; report those verification limits explicitly.

## Second acceptance round (0.4.2)

The settings gear keeps its upper-right position across phases; session controls use the other side. Live/Record is centered below the cloud, with a stable status region. The cloud has no press indication; its shared vector renderer uses deeper gray idle layers, richer purple capture layers, saturated red failure layers and faster/more pronounced continuous recording motion.

An attempted insertion that cannot be verified settles silently exactly like a confirmed insertion from the UI perspective. It is never retried or classified as a recovery error. Its text remains available passively in setup without changing the neutral logo or intercepting the next tap. Definite session failures turn the logo red without a text popup. Every idle tap starts a normal new session. Genuinely unsent text is retained separately in process memory and can be opened deliberately by holding the idle bubble, or in floating setup, for explicit Copy/Discard. Starting again and later successful/unverified writes do not erase older genuinely unsent recovery. Capture/key/editor resources close independently; process death still clears recovery. No durable history is introduced.

The main screen defaults to Record when no explicit mode preference exists, including upgrades: older versions did not persist mode choices, so there is no legacy persisted Live preference to migrate or reset. From 0.4.2, an explicit main-screen choice is persisted and respected on subsequent launches. A surviving existing ViewModel retains its selected mode. The keyboard bubble uses the same saved choice and defaults to Record too. It reuses the existing file recorder/transcriber: capture stays local until explicit Stop, then uploads once. Live remains available through the shared mode choice. No second floating selector or offline/history subsystem is introduced.

Three new input image downloads failed twice in this executor before readable bytes arrived; the parent image reviewer inspected all originals and confirmed the blocking unconfirmed-insertion panel and gear-to-menu/layout shift. No precise viewport clipping edge was inferred from still images.
