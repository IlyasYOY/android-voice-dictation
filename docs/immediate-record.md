# Immediate local Record

A deliberate tap checks permission and the shared history capacity, allocates/reserves a durable CAPTURING row, then starts MediaRecorder without waiting for key loading, network or provider readiness. Main and floating use the same session contract. There is one workflow and no mode preference at execution time.

Stop finalizes local AAC/M4A, checkpoints duration and requests file transcription. Missing/unreadable credentials or network/provider failure retain the clip for manual retry. A pending local credential operation may settle before this one Stop-requested attempt; it does not delay microphone start or authorize another attempt. Reconnect, key loading, restart and upgrade never retry old audio automatically.

Successful audio and text remain until explicit deletion. Background/interruption transfers recoverable audio to process-owned history without editor/credential ownership. Cancellation remains deliberate. Existing size/duration safety guards and generation checks remain; no emulator result guarantees first-word capture on every OEM.

The fixed file-transcription model and non-Live API configuration are preserved. See [storage](durable-recordings.md) and [compatibility](record-only.md).
