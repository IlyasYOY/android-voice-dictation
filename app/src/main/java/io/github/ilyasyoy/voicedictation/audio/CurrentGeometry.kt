package io.github.ilyasyoy.voicedictation.audio

import kotlin.math.*

/** Identical normalized outline in the Activity and overlay; callers only scale its coordinates. */
internal object CurrentGeometry {
    fun radius(angle: Double, layer: Int, calmPhase: Double, voicePhase: Double, levels: VoiceLevels, freshness: Float): Double {
        val calm = .065 * sin(3 * angle + calmPhase * .75 + layer * .18) + .045 * cos(2 * angle - calmPhase * .48)
        // Android y grows downward: bottom -> bass, top -> treble. Interpolate all nine bands.
        val local = levels.at(((1 - sin(angle)) * .5).toFloat()) * freshness.safeUnit()
        val loudness = levels.loudness.safeUnit() * freshness.safeUnit()
        // Add the already-smoothed measurements instead of multiplying them: ordinary speech
        // must remain visible at 58dp. No gain/noise gate changes to capture or the analyzer.
        val drive = .22 * loudness + .78 * local
        val wave = .65 * sin(3 * angle + voicePhase + layer * .16) +
            .35 * cos(5 * angle - voicePhase * .63 + layer * .12)
        // Inward AND outward motion, with headroom reserved by reducing calm movement as
        // sound takes over. Preserve the normalized bounds in both hosts, without flat peaks.
        return (1 + calm * (1 - .65 * drive) + .18 * drive * wave).coerceIn(.78, 1.22)
    }
}
