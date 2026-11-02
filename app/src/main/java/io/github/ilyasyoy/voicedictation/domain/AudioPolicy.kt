package io.github.ilyasyoy.voicedictation.domain

object AudioPolicy {
    const val MAX_SECONDS = 30 * 60
    const val BIT_RATE = 48_000
    const val FILE_BYTES = 20_000_000L
    const val UPLOAD_BYTES = 24_000_000L
    const val SAMPLE_RATE = 24_000
}
