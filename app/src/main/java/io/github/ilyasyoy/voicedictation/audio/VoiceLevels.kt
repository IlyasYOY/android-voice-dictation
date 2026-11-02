package io.github.ilyasyoy.voicedictation.audio

import kotlin.math.*

/** Local, transient measurements only. Never owns or starts a microphone. */
fun interface VoiceLevelSource {
    fun current(): VoiceLevels
    companion object { val Silent = VoiceLevelSource { VoiceLevels.Silence } }
}

/** Immutable normalized values; bands run from low to high. No PCM is exposed or retained. */
class VoiceLevels(val loudness: Float, bands: FloatArray, val measuredAtNanos: Long) {
    private val values = FloatArray(BAND_COUNT) { i -> bands.getOrElse(i) { 0f }.safeUnit() }
    fun band(index: Int): Float = values[index.coerceIn(0, BAND_COUNT - 1)]
    fun at(height: Float): Float {
        val position = height.safeUnit() * (BAND_COUNT - 1)
        val low = position.toInt()
        val t = position - low
        val smooth = t * t * (3 - 2 * t)
        return band(low) * (1 - smooth) + band(low + 1) * smooth
    }
    fun freshness(now: Long): Float {
        if (measuredAtNanos == 0L || now < measuredAtNanos) return 0f
        val age = (now - measuredAtNanos) / 1_000_000f
        return (1 - (age - 100f) / 200f).coerceIn(0f, 1f)
    }
    companion object {
        const val BAND_COUNT = 9
        val Silence = VoiceLevels(0f, FloatArray(BAND_COUNT), 0L)
    }
}

internal fun Float.safeUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f

/** 1024-point Hann-window FFT at 24 kHz: one measurement per 42.7ms, without allocations per sample. */
internal class VoiceSpectrum(private val sampleRate: Int = 24_000) {
    private val samples = DoubleArray(SIZE)
    private val real = DoubleArray(SIZE)
    private val imaginary = DoubleArray(SIZE)
    private val window = DoubleArray(SIZE) { .5 - .5 * cos(2 * PI * it / (SIZE - 1)) }
    private val edges = doubleArrayOf(70.0, 150.0, 260.0, 440.0, 730.0, 1200.0, 2000.0, 3300.0, 5500.0, 10000.0)
    private val smoothed = FloatArray(VoiceLevels.BAND_COUNT)
    private var volume = 0f
    private var count = 0
    fun reset() { samples.fill(0.0); smoothed.fill(0f); volume = 0f; count = 0 }
    fun accept(pcm: ShortArray, size: Int, nowNanos: Long, publish: (VoiceLevels) -> Unit) {
        require(size in 0..pcm.size)
        for (i in 0 until size) {
            samples[count++] = pcm[i] / 32768.0
            if (count == SIZE) { publish(analyze(nowNanos)); count = 0 }
        }
    }
    // Also used by deterministic numeric tests, including malformed floating-point input.
    internal fun analyzeFrame(pcm: FloatArray, nowNanos: Long): VoiceLevels {
        require(pcm.size == SIZE)
        for (i in samples.indices) samples[i] = if (pcm[i].isFinite()) pcm[i].coerceIn(-1f, 1f).toDouble() else 0.0
        return analyze(nowNanos)
    }
    private fun analyze(now: Long): VoiceLevels {
        val mean = samples.average()
        var sum = 0.0
        for (i in samples.indices) {
            val sample = samples[i] - mean // Reject DC; never depict a constant offset as bass.
            sum += sample * sample
            real[i] = sample * window[i]; imaginary[i] = 0.0
        }
        fft()
        val rms = sqrt(sum / SIZE)
        val target = perceptual(rms)
        volume = smooth(volume, target)
        val bands = FloatArray(VoiceLevels.BAND_COUNT)
        for (band in bands.indices) {
            var power = 0.0
            for (bin in 1 until SIZE / 2) {
                val frequency = bin.toDouble() * sampleRate / SIZE
                if (frequency >= edges[band] && frequency < edges[band + 1]) power += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }
            // Hann power correction (mean-square window = 3/8), one-sided spectrum.
            val level = if (target == 0f) 0f else perceptual(sqrt(power * 2 / (SIZE.toDouble() * SIZE * .375)))
            smoothed[band] = smooth(smoothed[band], level)
            bands[band] = smoothed[band]
        }
        return VoiceLevels(volume, bands, now)
    }
    private fun smooth(previous: Float, target: Float): Float {
        val seconds = SIZE.toDouble() / sampleRate
        val tau = if (target > previous) .045 else .18
        return (previous + (target - previous) * (1 - exp(-seconds / tau))).toFloat().safeUnit()
    }
    private fun perceptual(rms: Double): Float = if (rms < .0018) 0f else ((20 * log10(rms) + 55) / 45).toFloat().safeUnit()
    private fun fft() {
        var j = 0
        for (i in 1 until SIZE) {
            var bit = SIZE shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= SIZE) {
            val stepR = cos(-2 * PI / length); val stepI = sin(-2 * PI / length)
            for (start in 0 until SIZE step length) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k; val b = a + length / 2
                    val tr = wr * real[b] - wi * imaginary[b]; val ti = wr * imaginary[b] + wi * real[b]
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                    val next = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = next
                }
            }
            length *= 2
        }
    }
    companion object { const val SIZE = 1024 }
}
