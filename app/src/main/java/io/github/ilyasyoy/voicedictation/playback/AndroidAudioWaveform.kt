package io.github.ilyasyoy.voicedictation.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local, read-only thumbnail: 40 evenly spaced decoded frames, not a fabricated voice pattern.
 * Work is serial, cancellable and bounded; unavailable/unsupported audio gets a neutral track.
 * The small memory cache is only presentation data and never deletes a recording or transcript.
 */
internal object AndroidAudioWaveform {
    private data class Key(val path: String, val size: Long, val modified: Long)
    private val lock = Mutex()
    private val cache = linkedMapOf<Key, List<Float>>()
    suspend fun read(file: File): List<Float>? = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = Key(file.absolutePath, file.length(), file.lastModified())
            cache[key]?.let { return@withLock it }
            val result = try { decode(file) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (result != null) {
                if (cache.size >= 16) cache.remove(cache.keys.first())
                cache[key] = result
            }
            result
        }
    }

    private suspend fun decode(file: File): List<Float>? {
        if (!file.isFile || file.length() == 0L) return null
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        // Canonical PCM WAV needs no media-service IPC or codec. Sample its real
        // frames directly; other WAV layouts and compressed formats use the SDK.
        canonicalPcm(file, deadline)?.let { return it }
        if (SystemClock.elapsedRealtime() >= deadline) return null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (duration <= 0L) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            val raw = mime == "audio/raw"
            if (!raw) {
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
            }
            // AAC frames are independently addressable. Feed small windows through
            // one decoder run, rather than flushing/reconfiguring it forty times.
            // The first frame in each window restores overlap history; measure
            // the second. This bounds work even for long retained recordings.
            if (mime == "audio/mp4a-latm" && decoder != null) {
                return sampleAac(extractor, decoder, format, duration, deadline)
            }
            val pcm = ByteBuffer.allocate(65_536).order(ByteOrder.LITTLE_ENDIAN)
            val peaks = ArrayList<Float>(40)
            val info = MediaCodec.BufferInfo()
            for (index in 0 until 40) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) return null
                val target = duration * index / 40
                extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val codec = decoder
                if (codec == null) {
                    pcm.clear()
                    val size = extractor.readSampleData(pcm, 0)
                    if (size <= 0) return null
                    pcm.position(0); pcm.limit(size)
                    peaks += peak(pcm, encoding(format))
                    continue
                }
                if (index > 0) codec.flush()
                var inputEnded = false
                var found: Float? = null
                while (found == null) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) return null
                    if (!inputEnded) {
                        val input = codec.dequeueInputBuffer(10_000)
                        if (input >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(input))
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            inputEnded = size < 0
                            codec.queueInputBuffer(input, 0, size.coerceAtLeast(0), time.coerceAtLeast(0),
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val output = codec.dequeueOutputBuffer(info, 10_000)
                    if (output >= 0) {
                        try {
                            // seekTo already chose the preceding audio frame. Its PCM can
                            // straddle target; requiring a later frame rejects valid short
                            // clips when the final target lies inside their last frame.
                            if (info.size > 0) {
                                val buffer = checkNotNull(codec.getOutputBuffer(output)).order(ByteOrder.LITTLE_ENDIAN)
                                found = peak(buffer, encoding(codec.getOutputFormat(output)))
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && found == null) return null
                        } finally { codec.releaseOutputBuffer(output, false) }
                    }
                }
                peaks += found
            }
            val maximum = peaks.maxOrNull() ?: return null
            return if (maximum > 0f) peaks.map { (it / maximum).coerceIn(0f, 1f) } else peaks
        } finally {
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun sampleAac(extractor: MediaExtractor, codec: MediaCodec,
        format: MediaFormat, duration: Long, deadline: Long): List<Float>? {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (rate <= 0) return null
        val frameTime = 1_024_000_000L / rate
        val peaks = FloatArray(40)
        val measured = BooleanArray(40)
        val info = MediaCodec.BufferInfo()
        var slot = 0
        var ended = false
        while (!ended) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) return null
            val input = if (slot <= 80) codec.dequeueInputBuffer(0) else -1
            if (input >= 0) {
                if (slot < 80) {
                    if (slot % 2 == 0) {
                        val target = duration * (slot / 2) / 40
                        extractor.seekTo((target - frameTime).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    val buffer = checkNotNull(codec.getInputBuffer(input))
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size <= 0) return null
                    codec.queueInputBuffer(input, 0, size, slot * frameTime, 0)
                    // Reuse the final valid frame for a window at the clip tail.
                    if (extractor.sampleTime + frameTime < duration) extractor.advance()
                    slot++
                } else {
                    codec.queueInputBuffer(input, 0, 0, slot * frameTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    slot++
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output >= 0) {
                try {
                    if (info.size > 0) {
                        val outputSlot = ((info.presentationTimeUs + frameTime / 2) / frameTime).toInt()
                        if (outputSlot in 0 until 80 && outputSlot % 2 == 1) {
                            val buffer = checkNotNull(codec.getOutputBuffer(output)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bucket = outputSlot / 2
                            peaks[bucket] = peak(buffer, encoding(codec.getOutputFormat(output)))
                            measured[bucket] = true
                        }
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally { codec.releaseOutputBuffer(output, false) }
            }
        }
        if (!measured.all { it }) return null
        val maximum = peaks.maxOrNull() ?: return null
        return peaks.map { if (maximum > 0f) (it / maximum).coerceIn(0f, 1f) else it }
    }

    private suspend fun canonicalPcm(file: File, deadline: Long): List<Float>? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 44) return null
        val bytes = ByteArray(44); input.readFully(bytes)
        fun label(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (label(0) != "RIFF" || label(8) != "WAVE" || label(12) != "fmt " || label(36) != "data" ||
            header.getInt(16) != 16 || header.getShort(20).toInt() != 1 || header.getShort(34).toInt() != 16) return null
        val channels = header.getShort(22).toInt()
        val rate = header.getInt(24)
        if (channels !in 1..8 || rate !in 8000..192000) return null
        val alignment = channels * 2
        if (header.getShort(32).toInt() != alignment || header.getInt(28) != rate * alignment) return null
        val size = minOf(header.getInt(40).toLong() and 0xffffffffL, input.length() - 44)
        val frames = size / alignment
        if (frames == 0L) return null
        val samples = ByteArray(512 * alignment)
        val peaks = ArrayList<Float>(40)
        for (index in 0 until 40) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) return null
            val frame = frames * index / 40
            val count = minOf(512L, frames - frame).toInt() * alignment
            input.seek(44 + frame * alignment); input.readFully(samples, 0, count)
            peaks += peak(ByteBuffer.wrap(samples, 0, count).order(ByteOrder.LITTLE_ENDIAN), AudioFormat.ENCODING_PCM_16BIT)
        }
        val maximum = peaks.maxOrNull() ?: return null
        if (maximum > 0f) peaks.map { (it / maximum).coerceIn(0f, 1f) } else peaks
    }

    private fun encoding(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

    private fun peak(buffer: ByteBuffer, encoding: Int): Float {
        var maximum = 0f
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> while (buffer.remaining() >= 2) maximum = maxOf(maximum, abs(buffer.short.toInt()) / 32768f)
            AudioFormat.ENCODING_PCM_FLOAT -> while (buffer.remaining() >= 4) {
                val value = abs(buffer.float)
                if (value.isFinite()) maximum = maxOf(maximum, value.coerceAtMost(1f))
            }
            else -> throw IllegalArgumentException("Unsupported PCM encoding")
        }
        return maximum
    }
}
