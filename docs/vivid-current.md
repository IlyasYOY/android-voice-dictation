# Distinct Soft Current states

Candidate **0.15.0 (31)**, based on main after PR #15 (`1d1ebd6`). Main screen,
64dp keyboard overlay (58dp drawable after padding), and setup preview still share
`CurrentDrawable` and normalized `CurrentGeometry`.

- Idle / attention: neutral gray. Recording: saturated purple. Preparing / processing:
  yellow amber. Error: red. Processing has its own six-layer palette rather than
  a gray/purple blend. The central fill stays colored at small sizes.
- Each state has dark central strokes; outer pigment has at least 3:1 contrast
  against light/dark application backgrounds and surfaces. Color transitions keep
  the displayed starting pigment when interrupted, reaching about 93% of target in
  264ms. Reduced motion settles pigment immediately without reading voice levels.
- Already-smoothed RMS and local spectrum contribute additively, avoiding double
  attenuation of ordinary speech. The contour moves inward and outward; adjacent
  bands still interpolate smoothly. Calm motion yields space to voice deformation.
  The normalized `.78..1.22` radius and touch bounds remain. Central lines react
  more visibly too.

No changes to microphone ownership, capture, FFT bands, -55 to -10dBFS normalization,
noise gate, 45ms attack / 180ms release, freshness expiry, AAC/M4A, Record/Stop/Cancel,
upload, retry, history, or insertion behavior.

The chosen concept is a color direction, not an exact outline. Library resolved
`libfile_e45bddccd9ac819198174aed1b5739b1`, but the signed download host
`sdmntprnorthcentralus.oaiusercontent.com` was rejected by the cloud proxy
(CONNECT 403 Forbidden). Its pixels have **not** been inspected. Implementation
follows the explicitly approved gray / purple / amber / red brief; comparison
with the concept remains pending.

## Owner acceptance

On 2026-11-05 the owner confirmed the visual candidate was good and explicitly
requested merging PR #16. This records product approval, not a claim that every
manual scenario below was executed or that the cloud capture limitation passed.
The checklist remains useful for device and recording regressions.

## Phone acceptance (screenshots only)

1. Install the signed candidate as an update, retaining history. If the installed
   signing certificate differs, stop; do not uninstall to work around it.
2. In light and dark themes, check gray idle → purple Record → amber processing
   → red retained failure (airplane mode or missing key). In the keyboard overlay,
   identify processing versus error without opening its panel. A pending response
   keeps amber visible; a fast request may finish quickly.
3. Record silence, quiet, normal and loud speech. Contour and central lines should
   respond promptly and smoothly, strengthen with voice level, then gently release
   into calm motion during silence. Ambient sound can also cause reaction.
4. Hum a low vowel, then make a high “s” sound. Deformation should move from lower
   to upper contour. Repeat with the small keyboard button; check no clipping or
   jumps at loud levels.
5. Disable Android animations. Record, Stop and retained error remain identifiable
   by color with static outlines even while speaking. Restore animations and check
   a fresh recording. TalkBack Record/Stop/Cancel actions are unchanged.
6. Play the beginning/end of short and multi-minute recordings. Verify Stop,
   Cancel, explicit retry and retained failure behave as before in main and overlay.
   Phone microphone and subjective motion acceptance are separate from emulator
   results. No paid provider request is part of cloud checks.

## Automated evidence

`VoiceSpectrumTest` uses actual analyzer code with synthetic PCM for frequency,
quiet/loud excursion at overlay scale, continuity, headroom, silence/release/stale
signals. `VividCurrentRenderingTest` checks Android rasterization at 58 and 228px,
pigment families, contrast, transitions and reduced-motion polling isolation.
`VividCurrentScreenTest` captures actual main windows with labeled synthetic states
in light/dark and reduced motion. `AudioReactiveScreenTest` uses actual emulator
MIC capture (silence) and Cancel. Cross-app
`vividCurrentStatesAboveKeyboardInBothThemes` captures actual accessibility overlay
above the fixture IME, actual MIC, held localhost request and 503 retained failure.
The complete cross-app attempt is currently blocked by intermittent emulator
capture failure at Stop before HTTP; it is not a passed end-to-end check.
`NativeOverlayPaletteScreenTest` instead displays the actual shared `FloatingWindow`
inside an Activity-owned panel above a real IME, with explicitly synthetic states
and no microphone/HTTP. Its images must not be described as accessibility-service
recording evidence. These are screenshots, not video or provider proof.

On the unaccelerated API 28 cloud emulator the screenshot harness settles the UI
with animations disabled; the synthetic voice screenshot explicitly steps and
freezes the shared renderer. The fixture editor follows system day/night too.
Pixel checks avoid API 28's stale accessibility descriptions after color changes.
Overlay attempts with and without animations returned a recording error at Stop;
standalone Android capture/encoder checks passed. This is not a continuous-speech
device acceptance pass. Normal-motion capture and listening remain device-specific
validation; owner approval above covers this visual candidate. This change does
not relax capture guards or change recording code.

Counts, execution receipts, candidate commit and APK checksum are in the draft PR.
Test source or compilation alone is not execution.
