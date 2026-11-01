package io.github.ilyasyoy.voicedictation.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
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
    private val cache = linkedMapOf<Key, List<Float>?>()
    suspend fun read(file: File): List<Float>? = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = Key(file.absolutePath, file.length(), file.lastModified())
            if (cache.containsKey(key)) return@withLock cache[key]
            val result = try { decode(file) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (cache.size >= 16) cache.remove(cache.keys.first())
            cache[key] = result
            result
        }
    }

    private suspend fun decode(file: File): List<Float>? {
        if (!file.isFile || file.length() == 0L) return null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val deadline = SystemClock.elapsedRealtime() + 4_000L
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
                            if (info.size > 0 && info.presentationTimeUs >= target) {
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
