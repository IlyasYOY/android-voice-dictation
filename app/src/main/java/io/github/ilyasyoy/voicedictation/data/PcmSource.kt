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

/** Fresh AudioRecord per run. Non-blocking reads allow shutdown/release on the capture worker. */
class AndroidPcmSource : PcmSource {
    private val worker = PcmCaptureWorker(::capture)
    override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        io.github.ilyasyoy.voicedictation.playback.AndroidRecordingPlayback.interrupt()
        worker.start(onAudio, onError)
    }
    override fun stop() = worker.stop()

    @SuppressLint("MissingPermission") // Explicit caller permission gate; revocation is also handled as failure.
    private fun capture(): PcmCapture {
        val minimum = AudioRecord.getMinBufferSize(AudioPolicy.PCM_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioPolicy.PCM_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 9600)).build()
        return object : PcmCapture {
            override fun start() {
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording(); check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            }
            override fun read(buffer: ByteArray): Int = audio.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            override fun stop() { audio.stop() }
            override fun release() { audio.release() }
        }
    }
}
