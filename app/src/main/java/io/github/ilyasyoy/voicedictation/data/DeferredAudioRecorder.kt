package io.github.ilyasyoy.voicedictation.data

import java.io.File

/** Live-only owners never allocate file recording resources, including on Cancel/close. */
internal class DeferredAudioRecorder(private val create: () -> AudioRecorder) : AudioRecorder {
    private var recorder: AudioRecorder? = null
    override fun start(onLimit: () -> Unit) {
        val active = recorder ?: create().also { recorder = it }
        active.start(onLimit)
    }
    override fun stop(): File = checkNotNull(recorder).stop()
    override fun cancel() { recorder?.cancel() }
}
