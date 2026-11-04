# Stable Fold workspace (0.14.1 / 30)

The reported symptom is a jump in main-screen position and apparent scale after opening a Galaxy Z Fold fully and slightly closing the hinge. No physical Samsung device is attached to this environment, so the OEM event sequence and the user's exact symptom remain to be confirmed on that device.

## Reproduced cause and correction

At baseline `b75d654`, both MainActivity and AdaptiveHistoryLayout discarded non-separating folding features. A vertical FLAT crease therefore used the ordinary weighted main/history split. A HALF_OPENED event at the same window size introduced the crease: the policy abruptly aligned panes to the hinge, or switched to the larger single half plus drawer if pane minima did not fit. DictationScreen sizes its record control from the resulting available width/height, explaining the apparent scale change without a density or font-scale change.

An executed API28 Compose regression failed on that baseline at a fixed 616 dp window: the main bounds changed from `(0,0,84,180)` to `(92,0,185,180)` pixels in its synthetic 0.3-density host after only a FLAT → HALF_OPENED event. This is synthetic WindowManager geometry, not a physical-fold reproduction.

MainActivity now retains the actual reported vertical feature in FLAT, and the policy reserves its geometry in both postures. Real separation or FULL occlusion takes priority when selecting a feature. We do not infer a fold from the model, screen width, cached posture, or hinge angle. Coordinates remain window-relative and are translated into the current inset/IME-safe area. Off-window features are ignored; removing the feature restores ordinary width adaptation. A horizontal non-separating, non-occluding crease uses full height; tabletop separation or actual occlusion still selects an unobstructed region.

For a centered zero-width vertical crease at normal font scale, both postures now use:

| Safe window width | Dictation | History |
| --- | --- | --- |
| 616 dp | One 308 dp half | Drawer in the same half |
| 640 dp | Left 320 dp | Right 320 dp |
| 673 dp | Left 336.5 dp | Right 336.5 dp |
| 700 dp | Left 350 dp | Right 350 dp |

The deliberate tradeoff is reserving the crease while flat as well. With tight halves or large fonts, a compact workspace occupies one safe half even while flat. This preserves the existing `280 × fontScale` / `320 × fontScale` pane minima and avoids later crossing a separating fold. Pixel rounding and system insets affect exact boundary cases. Actual window, density, font, inset, orientation or feature-geometry changes can still require layout changes; absence of feature data cannot establish hinge-safe geometry.

The stress regression also exposed a Compose SlotWriter exception during disposal after repeatedly moving DictationScreen (which contains AndroidView) between the Material scaffold's subcomposition and a separate wide slot. The main body now stays in one BottomSheetScaffold. When the drawer is not applicable it has zero peek, no handle/content, gestures or Back handler. Its hidden anchor changes do not dismiss history or clear transient state. History itself remains a single active presentation with its existing shared UI state and ViewModel ownership.

## Checks and device acceptance

Regression sources include pure policy cases for the four widths at font scales 1, 1.2 and 2, repeated postures, actual resize/removal, horizontal separation, and FULL occlusion. Android scenarios check fixed-window pane/control bounds, resize plus Settings/expanded edited history continuity, feature selection, and an Activity receiving injected WindowManager events during one recording and configuration recreation. Recording uses a counting fake recorder and a transcriber that fails if called; it does not prove microphone hardware capture. Existing adaptive and drawer gesture scenarios cover the shared presentation paths. Exact executed results belong in the PR; compiling these sources alone is not a runtime pass.

For physical acceptance, update without clearing app data, open the main screen on the inner display, and repeat fully open → slightly bent → fully open, including while recording. Check that control/pane bounds do not jump at constant window geometry, recording continues, and Settings/edited history survive. Repeat at larger system fonts, in split-screen and with a horizontal/tabletop fold; real hinges and occlusions must not intersect controls or dialogs. Check the outer display after closing, and reopen the app after backgrounding. No video or paid provider request is required.
