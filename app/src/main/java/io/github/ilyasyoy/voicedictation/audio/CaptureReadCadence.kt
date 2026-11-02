package io.github.ilyasyoy.voicedictation.audio

import java.io.IOException

/** Bounded native-buffer budget, not a claim to measure hardware overruns precisely.
 * Empty nonblocking reads rebase the sample clock, avoiding accumulated clock drift on long clips.
 */
internal class CaptureReadCadence(bufferFrames: Int, private val sampleRate: Int, startNanos: Long) {
    init { require(bufferFrames > 0 && sampleRate > 0) }
    private val capacityNanos = bufferFrames.toLong() * 1_000_000_000 / sampleRate
    private var epochNanos = startNanos
    private var consumed = 0L
    fun read(frames: Int, nowNanos: Long) {
        require(frames >= 0)
        if (frames == 0) { epochNanos = nowNanos; consumed = 0 }
        else consumed += frames
        checkBudget(nowNanos)
    }
    /** Drop optional FFT work first; sustained encoder/storage pressure ends capture explicitly. */
    fun mayAnalyze(nowNanos: Long): Boolean {
        checkBudget(nowNanos)
        return backlogNanos(nowNanos) <= 100_000_000L
    }
    private fun backlogNanos(now: Long): Long =
        (now - epochNanos - consumed * 1_000_000_000 / sampleRate).coerceAtLeast(0)
    private fun checkBudget(now: Long) {
        if (backlogNanos(now) >= capacityNanos * 3 / 4) throw IOException("Microphone read backlog exceeded safe buffer budget")
    }
}
