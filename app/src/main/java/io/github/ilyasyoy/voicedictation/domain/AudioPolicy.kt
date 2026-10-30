package io.github.ilyasyoy.voicedictation.domain

object AudioPolicy {
    const val MAX_SECONDS = 30 * 60
    const val BIT_RATE = 48_000
    const val FILE_BYTES = 20_000_000L
    const val UPLOAD_BYTES = 24_000_000L
    const val PCM_RATE = 24_000
}

/** PCM16 little-endian mono: commit a turn after speech/silence or a bounded long turn. */
class AudioTurnDetector {
    private var samples = 0
    private var silenceSamples = 0
    private var speech = false
    fun append(pcm: ByteArray): Boolean {
        var energy = 0L
        val count = pcm.size / 2
        if (count == 0) return false
        for (i in pcm.indices step 2) {
            if (i + 1 >= pcm.size) break
            val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            energy += value.toLong() * value
        }
        val loud = energy / count > 300L * 300
        samples += count
        if (loud) { speech = true; silenceSamples = 0 } else silenceSamples += count
        if ((speech && silenceSamples >= AudioPolicy.PCM_RATE * 8 / 10) || samples >= AudioPolicy.PCM_RATE * 15) {
            reset(); return true
        }
        return false
    }
    fun reset() { samples = 0; silenceSamples = 0; speech = false }
}
