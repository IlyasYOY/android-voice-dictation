package io.github.ilyasyoy.voicedictation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.ColorUtils
import io.github.ilyasyoy.voicedictation.audio.*
import io.github.ilyasyoy.voicedictation.ui.design.*
import org.junit.Assert.*
import org.junit.Test

/** Android rasterization, including the 58dp drawable inside the 64dp overlay target. */
class VividCurrentRenderingTest {
    private fun render(visual: CurrentVisual, source: VoiceLevelSource, motion: Boolean, size: Int = 58): Bitmap {
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        CurrentDrawable().apply {
            this.visual = visual; levels = source; setBounds(0, 0, size, size)
            repeat(40) { advanceFrame(.033f, 1_050_000_000, motion) }
        }.draw(Canvas(image))
        return image
    }
    @Test fun stateFamiliesAndInkStayDistinctOnLightAndDarkSurfaces() {
        for (visual in listOf(CurrentVisual.IDLE, CurrentVisual.RECORDING, CurrentVisual.PROCESSING, CurrentVisual.ERROR)) {
            val layers = SoftCurrentPalette.layers(visual)
            for (surface in listOf(SoftCurrentPalette.Paper, SoftCurrentPalette.Surface, SoftCurrentPalette.Night, SoftCurrentPalette.NightSurface)) {
                assertTrue("$visual outer edge contrast", ColorUtils.calculateContrast(layers.first(), surface) >= 3.0)
            }
            assertTrue("$visual central lines", ColorUtils.calculateContrast(SoftCurrentPalette.ink(visual), layers.last()) >= 3.0)
        }
        val hues = listOf(CurrentVisual.RECORDING, CurrentVisual.PROCESSING, CurrentVisual.ERROR).map {
            FloatArray(3).also { hsv -> android.graphics.Color.colorToHSV(SoftCurrentPalette.layers(it).last(), hsv) }
        }
        assertTrue(hues[0][0] in 260f..290f)
        assertTrue(hues[1][0] in 38f..50f)
        assertTrue(hues[2][0] > 345f || hues[2][0] < 10f)
    }
    @Test fun smallControlRetainsEveryStateWithoutMotionOrMeterReads() {
        val forbidden = VoiceLevelSource { error("Reduced motion and non-recording states must not poll") }
        val states = listOf(CurrentVisual.IDLE, CurrentVisual.RECORDING, CurrentVisual.PROCESSING, CurrentVisual.ERROR)
        val images = states.map { render(it, forbidden, false) }
        try { for (a in images.indices) for (b in 0 until a) assertFalse(images[a].sameAs(images[b])) }
        finally { images.forEach(Bitmap::recycle) }
        for (visual in states.filter { it != CurrentVisual.RECORDING }) render(visual, forbidden, true).recycle()
    }
    @Test fun soundChangesSmallControlAndReducedMotionSuppressesIt() {
        val sound = VoiceLevelSource { VoiceLevels(.8f, FloatArray(9) { it / 8f }, 1_000_000_000) }
        for (size in listOf(58, 228)) {
            val speech = render(CurrentVisual.RECORDING, sound, true, size)
            val silence = render(CurrentVisual.RECORDING, VoiceLevelSource.Silent, true, size)
            val reduced = render(CurrentVisual.RECORDING, sound, false, size)
            val reducedSilent = render(CurrentVisual.RECORDING, VoiceLevelSource.Silent, false, size)
            try { assertFalse(speech.sameAs(silence)); assertTrue(reduced.sameAs(reducedSilent)) }
            finally { listOf(speech, silence, reduced, reducedSilent).forEach(Bitmap::recycle) }
        }
    }
    @Test fun processingChangesHuePromptlyAndInterruptedTransitionsStayContinuous() {
        val drawable = CurrentDrawable().apply { setBounds(0, 0, 58, 58) }
        fun pixels(): Bitmap = Bitmap.createBitmap(58, 58, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
        drawable.visual = CurrentVisual.RECORDING
        repeat(40) { drawable.advanceFrame(.033f, 1, true) }
        val before = pixels()
        drawable.visual = CurrentVisual.PROCESSING
        val immediate = pixels()
        assertTrue("State setter preserves transition start", before.sameAs(immediate))
        repeat(8) { drawable.advanceFrame(.033f, 1, true) }
        val processing = pixels()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(processing.getPixel(29, 38), hsv)
        assertTrue("Amber is already apparent after 264ms: ${hsv[0]}", hsv[0] in 30f..60f)
        drawable.visual = CurrentVisual.ERROR
        val interrupted = pixels()
        assertTrue(processing.sameAs(interrupted))
        listOf(before, immediate, processing, interrupted).forEach(Bitmap::recycle)
    }
}
