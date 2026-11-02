package io.github.ilyasyoy.voicedictation.audio

import kotlin.math.*

/** Identical normalized outline in the Activity and overlay; callers only scale its coordinates. */
internal object CurrentGeometry {
    fun radius(angle: Double, layer: Int, calmPhase: Double, voicePhase: Double, levels: VoiceLevels, freshness: Float): Double {
        val calm = .065 * sin(3 * angle + calmPhase * .75 + layer * .18) + .045 * cos(2 * angle - calmPhase * .48)
        // Android y grows downward: bottom -> bass, top -> treble. Interpolate all nine bands.
        val local = levels.at(((1 - sin(angle)) * .5).toFloat()) * freshness.safeUnit()
        val loudness = levels.loudness.safeUnit() * freshness.safeUnit()
        // Fixed, smooth phase offsets give organic asymmetry without unrelated random speaking.
        val asymmetry = .72 + .18 * sin(3 * angle + voicePhase + layer * .16) + .1 * cos(5 * angle - voicePhase * .63)
        val speech = loudness * (.018 + .12 * local) * asymmetry
        return (1 + calm + speech).coerceIn(.78, 1.22)
    }
}
