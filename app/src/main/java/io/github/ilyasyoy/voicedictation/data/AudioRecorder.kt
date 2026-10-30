package io.github.ilyasyoy.voicedictation.data

import android.content.Context
import android.media.MediaRecorder
import java.io.File

interface AudioRecorder {
    fun start(onLimit: () -> Unit)
    fun stop(): File
    fun cancel()
}

/** Foreground Activity only. Never records from a service or on app launch. */
class AndroidAudioRecorder(context: Context) : AudioRecorder {
    private val directory = File(context.cacheDir, "dictation").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() } // Recover recordings orphaned by process death.
    }
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    @Suppress("DEPRECATION")
    override fun start(onLimit: () -> Unit) {
        check(recorder == null)
        val target = File.createTempFile("voice-", ".m4a", directory)
        file = target
        val capture = MediaRecorder()
        recorder = capture
        try {
            capture.setAudioSource(MediaRecorder.AudioSource.MIC)
            capture.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            capture.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            capture.setAudioSamplingRate(44100)
            capture.setAudioEncodingBitRate(96000)
            capture.setMaxDuration(120_000)
            capture.setMaxFileSize(20_000_000)
            capture.setOutputFile(target.absolutePath)
            capture.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
            }
            capture.setOnErrorListener { _, _, _ -> onLimit() }
            capture.prepare()
            capture.start()
        } catch (e: Exception) { cancel(); throw e }
    }

    override fun stop(): File {
        val active = checkNotNull(recorder)
        val target = checkNotNull(file)
        try {
            active.stop()
            require(target.length() > 0)
            return target
        } catch (e: Exception) { target.delete(); throw e }
        finally { active.release(); recorder = null; file = null }
    }

    override fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }
}
