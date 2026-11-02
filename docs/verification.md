# Verification

Run `scripts/check.sh` for app/fixture debug builds, lint, JVM suites and Android-test APK compilation. Report exact head, counts, failures/skips and CI result. Run connected Android scenarios only with an available stable device; compile-only is not execution.

Current coverage preserves local capture-before-key/network, retained audio/text, explicit retry, key failure/deferral, atomic storage/deletion, original-editor one-attempt insertion, playback/waveform, onboarding and floating position. Record-only coverage adds old preference cleanup and raw legacy text/audio history with no automatic requests.

This environment currently lacks a stable connected Android/KVM after exhausted SystemUI/WindowManager failures. New runtime screenshots/results must not be fabricated or substituted with generated layouts. Test fixtures use synthetic text/keys and localhost HTTP only, never paid provider requests.
