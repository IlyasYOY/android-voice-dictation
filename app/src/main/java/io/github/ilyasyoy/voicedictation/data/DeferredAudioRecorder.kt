package io.github.ilyasyoy.voicedictation.data

import java.io.File

/** Idle owners never allocate recording resources, including visual previews and Cancel/close. */
internal class DeferredAudioRecorder(private val create: () -> AudioRecorder) : AudioRecorder {
    private var recorder: AudioRecorder? = null
    override val levels = io.github.ilyasyoy.voicedictation.audio.VoiceLevelSource {
        recorder?.levels?.current() ?: io.github.ilyasyoy.voicedictation.audio.VoiceLevels.Silence
    }
    override fun start(onLimit: () -> Unit) {
        val active = recorder ?: create().also { recorder = it }
        active.start(onLimit)
    }
    override fun stop(): File = checkNotNull(recorder).stop()
    override fun cancel() { recorder?.cancel() }
}
