package io.github.ilyasyoy.voicedictation.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.ilyasyoy.voicedictation.domain.AudioPolicy

interface PcmSource {
    fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit)
    fun stop()
}
class AndroidPcmSource : PcmSource {
    @Volatile private var running = false
    private var audio: AudioRecord? = null
    private var worker: Thread? = null
    @SuppressLint("MissingPermission") // MainActivity requests permission only after explicit Record.
    override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        check(audio == null)
        val minimum = AudioRecord.getMinBufferSize(AudioPolicy.PCM_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        val capture = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioPolicy.PCM_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 9600)).build()
        audio = capture
        try {
            check(capture.state == AudioRecord.STATE_INITIALIZED)
            capture.startRecording(); check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            running = true
            worker = Thread({
                val buffer = ByteArray(4800) // 100 ms, PCM16 LE mono.
                try {
                    while (running) {
                        val count = capture.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) onAudio(buffer.copyOf(count))
                        else if (running) throw java.io.IOException("Audio capture stopped")
                    }
                } catch (e: Exception) { if (running) onError(e) }
            }, "dictation-pcm").apply { isDaemon = true; start() }
        } catch (e: Exception) { stop(); throw e }
    }
    override fun stop() {
        running = false
        audio?.let { runCatching { it.stop() } }
        if (worker != Thread.currentThread()) worker?.join(500)
        worker = null
        audio?.release(); audio = null
    }
}
