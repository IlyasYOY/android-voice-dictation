package io.github.ilyasyoy.voicedictation.audio

/** A timeout must not expose a still-writing clip to history Retry/Delete or release its capacity. */
internal class StoppedRecordingRecovery(private val retain: () -> Unit) {
    private var released = false
    private var requested = false
    private var retained = false
    @Synchronized fun afterRelease() { released = true; transferIfReady() }
    @Synchronized fun request() { requested = true; transferIfReady() }
    private fun transferIfReady() {
        if (released && requested && !retained) { retain(); retained = true }
    }
}
