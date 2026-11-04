# Soft Current

Milk/lavender surfaces, plum text, soft rounding and a shared organic vector control define the app. The main screen is a quiet recording surface: accessible Record/Stop, status/timer while active, generic red failure, and the compact saved-count/history entrance. There is no mode selector or extra decorative Record label.

`CurrentDrawable` uses [distinct gray/purple/amber/red pigments](vivid-current.md), layered shapes and continuous state transitions for idle, key preparation, recording, processing, attention and failure. It is not a microphone-amplitude meter or spinner. Reduced motion keeps a distinct static palette. The floating button and its non-interactive setup preview use the same renderer; the preview stays idle and cannot start audio or networking.

API key and capacity settings stay in accessible Settings. Onboarding and later floating setup share the same card/layout/action components; permissions are focused on one missing step rather than a permanent dashboard. Background restrictions are explained without claiming an unknown grant. Save/Forget failures remain visible and generic.

History is a bottom sheet of audio-message bubbles with aligned Play/waveform/text-toggle controls. Actual saved-audio waveform decoding is separate from the organic state animation. Text-only historical records retain text expansion, Copy and confirmed Delete without fake audio controls. Main/history content scrolls and respects insets/IME/large font.

The overlay remembers a deliberate drag, remains reachable across geometry changes and preserves safe original-editor insertion. Hold opens shared history/recovery; uncertain writes never retry. See [Record-only compatibility](record-only.md), [history](recording-history.md), [position](floating-position.md).
