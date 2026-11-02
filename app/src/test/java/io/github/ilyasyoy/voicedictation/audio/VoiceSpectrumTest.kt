package io.github.ilyasyoy.voicedictation.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VoiceSpectrumTest {
    private fun tone(frequency: Double, amplitude: Float = .12f) = FloatArray(VoiceSpectrum.SIZE) { (sin(2 * PI * frequency * it / 24_000) * amplitude).toFloat() }
    private fun settle(analyzer: VoiceSpectrum, frame: FloatArray): VoiceLevels {
        var result = VoiceLevels.Silence
        repeat(12) { result = analyzer.analyzeFrame(frame, (it + 1L) * 42_666_667) }
        return result
    }
    @Test fun silenceAndDcDoNotCreateMotion() {
        for (frame in listOf(FloatArray(1024), FloatArray(1024) { .4f })) {
            val levels = settle(VoiceSpectrum(), frame)
            assertEquals(0f, levels.loudness, 0f)
            repeat(9) { assertEquals(0f, levels.band(it), 0f) }
        }
    }
    @Test fun tonesMapToNineLowToHighRegions() {
        val frequencies = listOf(100.0, 200.0, 340.0, 560.0, 940.0, 1550.0, 2600.0, 4300.0, 7400.0)
        frequencies.forEachIndexed { expected, frequency ->
            val levels = settle(VoiceSpectrum(), tone(frequency))
            val actual = (0..8).maxBy { levels.band(it) }
            assertEquals("frequency=$frequency", expected, actual)
            assertTrue(levels.loudness > .6f)
        }
    }
    @Test fun loudnessChangesStrengthWithoutInventingOtherBands() {
        val quiet = settle(VoiceSpectrum(), tone(940.0, .008f))
        val loud = settle(VoiceSpectrum(), tone(940.0, .15f))
        assertTrue(loud.loudness > quiet.loudness + .4f)
        assertTrue(loud.band(4) > quiet.band(4) + .4f)
        assertEquals(0f, loud.band(0), .01f)
        assertEquals(0f, loud.band(8), .01f)
    }
    @Test fun mixedSpeechLikeSpectrumMeasuresSimultaneousRegions() {
        val frame = FloatArray(1024) { i ->
            (.08*sin(2*PI*100*i/24000) + .055*sin(2*PI*940*i/24000) + .035*sin(2*PI*4300*i/24000)).toFloat()
        }
        val levels = settle(VoiceSpectrum(), frame)
        for (band in listOf(0, 4, 7)) assertTrue("band=$band", levels.band(band) > .4f)
        assertTrue(levels.band(0) > levels.band(7))
    }
    @Test fun attackIsResponsiveAndReleaseIsSmoothThenSilent() {
        val analyzer = VoiceSpectrum()
        val first = analyzer.analyzeFrame(tone(940.0), 1)
        val peak = settle(analyzer, tone(940.0))
        val released = analyzer.analyzeFrame(FloatArray(1024), 600_000_000)
        assertTrue(first.loudness > .35f && first.loudness < peak.loudness)
        assertTrue(released.loudness in .4f..peak.loudness)
        assertTrue(released.loudness < peak.loudness)
        repeat(40) { analyzer.analyzeFrame(FloatArray(1024), 1_000_000_000) }
        val silent = analyzer.analyzeFrame(FloatArray(1024), 2_000_000_000)
        assertEquals(0f, silent.loudness, .001f)
        analyzer.reset()
        assertEquals(0f, analyzer.analyzeFrame(FloatArray(1024), 3_000_000_000).loudness, 0f)
    }
    @Test fun clippedAndNonfiniteInputProducesFiniteBoundedValues() {
        val frame = FloatArray(1024) { when(it % 5) {0 -> Float.NaN;1 -> Float.POSITIVE_INFINITY;2 -> 90f;3 -> -90f;else -> 0f} }
        val levels = settle(VoiceSpectrum(), frame)
        assertTrue(levels.loudness.isFinite() && levels.loudness in 0f..1f)
        repeat(9) { assertTrue(levels.band(it).isFinite() && levels.band(it) in 0f..1f) }
    }
    @Test fun chunkBoundariesDoNotChangeSpectrumOrRetainPcmReference() {
        val pcm = ShortArray(2048) { (sin(2*PI*940*it/24000)*4000).toInt().toShort() }
        val split = VoiceSpectrum(); val whole = VoiceSpectrum()
        val a = mutableListOf<VoiceLevels>(); val b = mutableListOf<VoiceLevels>()
        split.accept(pcm.copyOfRange(0, 417), 417, 1, a::add)
        split.accept(pcm.copyOfRange(417, 2048), 1631, 1, a::add)
        whole.accept(pcm, pcm.size, 1, b::add)
        pcm.fill(0)
        assertEquals(2, a.size); assertEquals(2, b.size)
        for (i in a.indices) repeat(9) { assertEquals(a[i].band(it), b[i].band(it), 0f) }
    }
    @Test fun bandsAreImmutableAndFreshnessExpiresWithin300ms() {
        val source = FloatArray(9) { 1f }
        val levels = VoiceLevels(.6f, source, 1_000_000_000)
        source.fill(0f)
        assertEquals(1f, levels.band(4), 0f)
        assertEquals(1f, levels.freshness(1_100_000_000), 0f)
        assertEquals(.5f, levels.freshness(1_200_000_000), .001f)
        assertEquals(0f, levels.freshness(1_300_000_000), 0f)
        assertEquals(0f, levels.freshness(900_000_000), 0f)
        assertEquals(0f, VoiceLevels.Silence.freshness(1_000_000_000), 0f)
    }
    @Test fun interpolationIsContinuousAtEveryBandBoundary() {
        val levels = VoiceLevels(1f, FloatArray(9) { if(it%2==0) 1f else 0f }, 1)
        for (i in 1..7) assertEquals(levels.at(i/8f-.0001f), levels.at(i/8f+.0001f), .001f)
        assertEquals(1f, levels.at(Float.NaN), 0f)
    }
    @Test fun geometryUsesVerticalSpectrumAndStaysWithinBoundsAtEveryAngle() {
        val bass = VoiceLevels(1f, FloatArray(9) { if(it==0)1f else 0f }, 1)
        val treble = VoiceLevels(1f, FloatArray(9) { if(it==8)1f else 0f }, 1)
        fun r(a: Double, level: VoiceLevels) = CurrentGeometry.radius(a, 0, 0.0, .7, level, 1f)
        assertTrue(r(PI/2, bass) > r(PI/2, treble) + .04)
        assertTrue(r(-PI/2, treble) > r(-PI/2, bass) + .04)
        for (i in 0..720) {
            val a = i * PI/360
            val all = VoiceLevels(1f, FloatArray(9) { 1f }, 1)
            assertTrue(r(a, all) in .78..1.22)
            // A stale signal and silence produce exactly the same calm shape.
            assertEquals(CurrentGeometry.radius(a, 0, 0.0, .7, all, 0f), r(a, VoiceLevels.Silence), 0.0)
        }
        assertNotEquals(r(PI/4, bass), r(3*PI/4, bass), .0001)
    }
}
