# VD008 recording history

Main opens a shared Soft Current bottom sheet by its Saved recordings button or an upward swipe at the history entry. Upward scrolling beyond the main content also opens history. The button is available with zero entries for accessibility and empty-state discovery. The gear and centered Live/Record control retain their positions. History is unavailable during main capture/processing.

Holding the idle keyboard bubble opens the same sheet in its own activity. No editor identity travels to the sheet. Explicit retry returns a passive result, never insertion into an old editor.

Cards read the existing durable RecordingRepository, newest first, with time, status, duration, audio playback, transcript, Copy and confirmed Delete. Pending audio offers Transcribe; failed/interrupted audio offers Retry. The recovery owner enforces a single manual retry and preserves its existing token/cancellation/storage semantics. No reconnect/startup upload or eviction is added. Successful audio and transcript stay until explicit deletion. Live cards honestly show text-only storage. Missing audio is unavailable; interrupted audio may be incomplete; decoder failure preserves the original bytes.

Playback uses a separate serial controller with generation-checked callbacks and an Android MediaPlayer adapter. Only one history owner can play. Closing the sheet/activity, backgrounding, deletion, retry, focus loss and every actual Record/Live microphone start stop and release playback. Pausing abandons audio focus. Playback does not acquire a recorder, key, editor or network connection.

A LazyColumn keeps long histories scrollable without loading a page limit. Transcript previews expand explicitly and support selection. Actions wrap on narrow/large-font layouts. Sheet nested scrolling allows list scrolling before downward dismissal. Existing theme colors and reduced-motion behavior are retained; playback progress has no decorative animation.

Validation is recorded in the delivery report outside Git. Instrumentation uses synthetic PCM and text, the actual durable ledger/MediaPlayer/clipboard and a local HTTP fixture. It does not establish real-device acoustics, first-word latency or OEM compatibility. No paid API calls or real dictation content are included.
