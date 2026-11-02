package io.github.ilyasyoy.voicedictation.data

import android.annotation.SuppressLint
import android.media.*
import android.os.SystemClock
import io.github.ilyasyoy.voicedictation.audio.PcmRecordingDriver
import io.github.ilyasyoy.voicedictation.domain.AudioPolicy
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Single MIC -> PCM16 -> AAC-LC/M4A path. Analysis never opens another audio source. */
internal class AndroidPcmRecordingDriver : PcmRecordingDriver {
    private var input: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var encoderStarted = false
    private var muxerStarted = false
    private var track = -1
    private var samples = 0L
    private var encodedSamples = 0
    private var eos = false
    private val info = MediaCodec.BufferInfo()
    override var bytesWritten = 0L
        private set

    @SuppressLint("MissingPermission") // Explicit permission/user gesture is checked by both session owners.
    fun start(target: File) {
        try {
            startEncoding(target)
            val minimum = AudioRecord.getMinBufferSize(AudioPolicy.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioPolicy.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 16384)).build()
            checkNotNull(input).let {
                check(it.state == AudioRecord.STATE_INITIALIZED)
                it.startRecording(); check(it.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            }
        } catch (e: Exception) { runCatching { close() }; throw e }
    }
    /** Separated for actual Android codec tests with deterministic local PCM; never starts a mic. */
    internal fun startEncoding(target: File) {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioPolicy.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AudioPolicy.BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            checkNotNull(encoder).configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            checkNotNull(encoder).start(); encoderStarted = true
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) { runCatching { close() }; throw e }
    }
    override fun read(buffer: ShortArray): Int = checkNotNull(input).read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
    override fun write(buffer: ShortArray, size: Int) {
        var offset = 0
        while (offset < size) {
            val index = awaitInput()
            val target = checkNotNull(checkNotNull(encoder).getInputBuffer(index)).order(ByteOrder.nativeOrder())
            target.clear()
            val count = minOf(size - offset, target.remaining() / 2)
            check(count > 0)
            for (i in 0 until count) target.putShort(buffer[offset + i])
            checkNotNull(encoder).queueInputBuffer(index, 0, count * 2, samples * 1_000_000 / AudioPolicy.SAMPLE_RATE, 0)
            samples += count; offset += count
            drain(0)
        }
    }
    private fun awaitInput(): Int {
        val until = SystemClock.elapsedRealtime() + 500
        while (SystemClock.elapsedRealtime() < until) {
            val index = checkNotNull(encoder).dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(0)
        }
        throw IOException("Audio encoder stalled")
    }
    private fun drain(timeoutUs: Long) {
        val codec = checkNotNull(encoder)
        // Bound each drain call even if a faulty codec produces an unending output stream.
        repeat(64) {
            when (val index = codec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = checkNotNull(muxer).addTrack(codec.outputFormat)
                    checkNotNull(muxer).start(); muxerStarted = true
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val output = checkNotNull(codec.getOutputBuffer(index))
                            output.position(info.offset); output.limit(info.offset + info.size)
                            checkNotNull(muxer).writeSampleData(track, output, info)
                            bytesWritten += info.size; encodedSamples++
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (eos) return
                }
            }
        }
    }
    override fun stopInput() {
        input?.let { mic ->
            // Release the microphone even when stop throws; cleanup always attempts all other resources.
            input = null
            try { if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop() }
            finally { mic.release() }
        }
    }
    override fun finish() {
        val index = awaitInput()
        checkNotNull(encoder).queueInputBuffer(index, 0, 0, samples * 1_000_000 / AudioPolicy.SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val until = SystemClock.elapsedRealtime() + 750
        while (!eos && SystemClock.elapsedRealtime() < until) drain(10_000)
        check(eos && muxerStarted && encodedSamples > 0) { "Audio finalization failed" }
        checkNotNull(muxer).stop(); muxerStarted = false
    }
    override fun close() {
        var failed: Exception? = null
        fun attempt(block: () -> Unit) { try { block() } catch (e: Exception) { if (failed == null) failed = e } }
        attempt { stopInput() }
        encoder?.let { codec ->
            if (encoderStarted) attempt { codec.stop() }
            attempt { codec.release() }
        }
        encoder = null; encoderStarted = false
        muxer?.let { container ->
            if (muxerStarted) attempt { container.stop() }
            attempt { container.release() }
        }
        muxer = null; muxerStarted = false
        failed?.let { throw it }
    }
}
