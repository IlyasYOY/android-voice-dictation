# Waveform seeking

History uses the existing waveform as a 48 dp high seek control, with a visible position marker and
played-waveform progress. Tap chooses a position; a horizontal drag previews it and commits one seek
on release. Cancelling a drag leaves the transport unchanged. Vertical gestures still belong to the
history list/drawer. Elapsed/total time follows the preview. No additional screen is introduced.

Seeking an active recording preserves play/pause intent. Selecting another recording by its waveform
releases the old player and prepares the new selection **paused**. Seeking exactly to the end pauses;
Play then rewinds before resuming. Natural completion keeps the existing stop/release behavior.
Recording startup, history dismissal/background, deletion and audio focus interruption still release
the player. A waveform cannot seek while a microphone capture or the row's upload is active.

The decoded MediaPlayer duration defines bounds, including sub-second audio. Seeks are asynchronous:
one seek is in flight, repeated requests coalesce to the latest position, progress ticks cannot replace
the pending position, and callbacks from released selections cannot resume playback. No audio is
rewritten or uploaded. Decoder errors retain the recording and use the existing playback error UI.

TalkBack sees a labelled adjustable progress control with elapsed/total time and explicit back/forward
10-second actions (clamped at the ends). Unknown/sub-second metadata does not disable normal seeking.

## Phone acceptance

1. Open history and tap different points on a saved waveform before playing. The cursor/time should
   move while the recording stays paused. Press Play, then seek again: playback should continue there.
2. Pause and drag to another point. Move outside the waveform to reach either end. The cursor follows
   the finger; releasing applies the position and keeps pause. Cancel an in-progress drag: no seek.
3. Seek to the end, then press Play: it starts at the beginning. Check a recording shorter than a second.
4. While one record plays, choose another waveform: only the new selection is retained and it is paused.
   Check closing history, changing screens, deleting and starting a new recording still stop playback.
5. Use vertical scrolling from the waveform; verify the list/drawer scrolls without accidental seeking.
   Check light/dark themes, large font and narrow/Fold layouts, plus TalkBack adjust/back/forward actions.

Automated verification uses synthetic local PCM and fake deterministic drivers, without provider calls
or real user recordings. Physical TalkBack and OEM/audio-device behavior require phone acceptance.
