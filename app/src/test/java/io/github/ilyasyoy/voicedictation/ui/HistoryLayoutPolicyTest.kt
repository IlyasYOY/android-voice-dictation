package io.github.ilyasyoy.voicedictation.ui

import org.junit.Assert.*
import org.junit.Test

class HistoryLayoutPolicyTest {
    private fun plan(width: Float, font: Float = 1f, hinge: HistoryHinge? = null) = HistoryLayoutPolicy.calculate(width, 700f, font, hinge)
    @Test fun `expanded boundary uses current available width`() {
        for (width in listOf(280f, 600f, 839.99f)) assertFalse(plan(width).wide)
        for (width in listOf(840f, 1000f, 1800f)) {
            val p = plan(width); assertTrue(p.wide)
            assertTrue(p.mainWidth >= 320f); assertTrue(p.historyWidth >= 360f)
            assertEquals(width, p.mainWidth + p.gap + p.historyWidth, .01f)
        }
        assertFalse(plan(700f).wide) // Even on an unfolded device, a small multi-window stays compact.
    }
    @Test fun `large font retains usable pane minima and can fall back`() {
        assertFalse(plan(1000f, 2f).wide)
        val p = plan(1400f, 2f); assertTrue(p.wide)
        assertTrue(p.mainWidth >= 640f); assertTrue(p.historyWidth >= 720f)
        assertTrue(plan(840f, Float.NaN).wide)
    }
    @Test fun `vertical separating hinge becomes the gap`() {
        val p = plan(1000f, hinge = HistoryHinge(480f, 0f, 520f, 700f, true))
        assertTrue(p.wide); assertEquals(480f, p.mainWidth); assertEquals(40f, p.gap); assertEquals(480f, p.historyWidth)
    }
    @Test fun `unusable hinge split uses one unobstructed region`() {
        val p = plan(900f, hinge = HistoryHinge(200f, 0f, 230f, 700f, true))
        assertFalse(p.wide); assertEquals(HistoryRegion(230f, 0f, 670f, 700f), p.region)
    }
    @Test fun `horizontal hinge never crosses controls`() {
        val p = plan(1000f, hinge = HistoryHinge(0f, 300f, 1000f, 340f, false))
        assertTrue(p.wide); assertEquals(HistoryRegion(0f, 340f, 1000f, 360f), p.region)
    }
    @Test fun `off-window hinge and zero-sized resize are harmless`() {
        assertEquals(plan(1000f), plan(1000f, hinge = HistoryHinge(1100f, 0f, 1120f, 700f, true)))
        val empty = HistoryLayoutPolicy.calculate(0f, 0f, 1f)
        assertFalse(empty.wide); assertEquals(0f, empty.mainWidth)
    }
}
