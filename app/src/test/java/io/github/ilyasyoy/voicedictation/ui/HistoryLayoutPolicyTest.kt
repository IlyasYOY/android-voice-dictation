package io.github.ilyasyoy.voicedictation.ui

import org.junit.Assert.*
import org.junit.Test

class HistoryLayoutPolicyTest {
    private fun plan(width: Float, font: Float = 1f, hinge: HistoryHinge? = null) = HistoryLayoutPolicy.calculate(width, 700f, font, hinge)
    @Test fun `book fold posture alone never changes layout across medium widths and fonts`() {
        for (width in listOf(616f, 640f, 673f, 700f)) for (font in listOf(1f, 1.2f, 2f)) {
            val flat = HistoryHinge(width / 2, 0f, width / 2, 700f, true, separating = false, occluding = false)
            val expected = plan(width, font, flat)
            repeat(5) {
                assertEquals(expected, plan(width, font, flat.copy(separating = true)))
                assertEquals(expected, plan(width, font, flat))
            }
            assertEquals(width >= 640f && font == 1f, expected.wide)
            assertEquals(width / 2, expected.mainWidth, .01f)
        }
    }
    @Test fun `resizing and losing a fold recomputes safe geometry instead of caching a posture layout`() {
        val flat = HistoryHinge(350f, 0f, 350f, 700f, true, separating = false, occluding = false)
        assertTrue(plan(700f, hinge = flat).wide)
        val narrow = plan(500f, hinge = flat.copy(left = 250f, right = 250f))
        assertFalse(narrow.wide); assertEquals(250f, narrow.mainWidth, .01f)
        assertEquals(plan(300f), plan(300f, hinge = flat)) // Feature is outside the resized window.
        assertEquals(700f, plan(700f, 2f).mainWidth, .01f) // Cover display reports no feature.
        assertEquals(plan(700f, hinge = flat), plan(700f, hinge = flat.copy(separating = true)))
    }
    @Test fun `flat horizontal crease uses full height but separation or occlusion reserves a safe side`() {
        val flat = HistoryHinge(0f, 300f, 1000f, 340f, false, separating = false, occluding = false)
        assertEquals(plan(1000f), plan(1000f, hinge = flat))
        val safe = HistoryRegion(0f, 340f, 1000f, 360f)
        assertEquals(safe, plan(1000f, hinge = flat.copy(separating = true)).region)
        assertEquals(safe, plan(1000f, hinge = flat.copy(occluding = true)).region)
    }
    @Test fun `flat occluding book hinge reserves its entire width at both postures`() {
        val flat = HistoryHinge(300f, 0f, 340f, 700f, true, separating = false, occluding = true)
        val p = plan(700f, hinge = flat)
        assertEquals(300f, p.mainWidth, .01f); assertEquals(40f, p.gap, .01f)
        assertEquals(360f, p.historyWidth, .01f)
        assertEquals(p, plan(700f, hinge = flat.copy(separating = true)))
    }
    @Test fun `usable pane minima use current available width without a tablet cutoff`() {
        for (width in listOf(280f, 360f, 600f, 615.99f)) assertFalse(plan(width).wide)
        for (width in listOf(616f, 640f, 673f, 700f, 704f, 839f, 839.99f, 840f, 1000f, 1800f)) {
            val p = plan(width); assertTrue(p.wide)
            assertTrue(p.mainWidth >= 280f); assertTrue(p.historyWidth >= 320f)
            assertEquals(width, p.mainWidth + p.gap + p.historyWidth, .01f)
        }
        assertFalse(plan(600f).wide) // A small multi-window stays compact, regardless of device.
    }
    @Test fun `large font retains usable pane minima and can fall back`() {
        assertFalse(plan(1000f, 2f).wide)
        val p = plan(1400f, 2f); assertTrue(p.wide)
        assertTrue(p.mainWidth >= 560f); assertTrue(p.historyWidth >= 640f)
        assertTrue(plan(840f, Float.NaN).wide)
    }
    @Test fun `scaled minima keep the exact boundary and medium windows safe`() {
        assertFalse(plan(700f, 1.2f).wide)
        assertFalse(plan(1215.99f, 2f).wide)
        val p = plan(1216f, 2f)
        assertTrue(p.wide); assertEquals(560f, p.mainWidth, .01f); assertEquals(640f, p.historyWidth, .01f)
    }
    @Test fun `medium window hinge uses each side rather than the old cutoff`() {
        val p = plan(700f, hinge = HistoryHinge(330f, 0f, 350f, 700f, true))
        assertTrue(p.wide); assertEquals(330f, p.mainWidth, .01f); assertEquals(350f, p.historyWidth, .01f)
        val narrowSide = plan(700f, hinge = HistoryHinge(380f, 0f, 400f, 700f, true))
        assertFalse(narrowSide.wide)
        val crease = plan(600f, hinge = HistoryHinge(280f, 0f, 280f, 700f, true))
        assertTrue(crease.wide) // The physical separating feature replaces ordinary spacing.
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
    @Test fun `onboarding never allocates a history pane and still avoids hinges`() {
        for (width in listOf(360f, 700f, 1400f)) {
            val p = HistoryLayoutPolicy.calculate(width, 800f, 1f, allowHistoryPane = false)
            assertFalse(p.wide); assertEquals(width, p.mainWidth, .01f)
        }
        val folded = HistoryLayoutPolicy.calculate(1000f, 700f, 1f,
            HistoryHinge(480f, 0f, 520f, 700f, true), allowHistoryPane = false)
        assertFalse(folded.wide); assertEquals(HistoryRegion(0f, 0f, 480f, 700f), folded.region)
        val tabletop = HistoryLayoutPolicy.calculate(1000f, 700f, 1f,
            HistoryHinge(0f, 300f, 1000f, 340f, false), allowHistoryPane = false)
        assertFalse(tabletop.wide); assertEquals(HistoryRegion(0f, 340f, 1000f, 360f), tabletop.region)
    }
    @Test fun `off-window hinge and zero-sized resize are harmless`() {
        assertEquals(plan(1000f), plan(1000f, hinge = HistoryHinge(1100f, 0f, 1120f, 700f, true)))
        val empty = HistoryLayoutPolicy.calculate(0f, 0f, 1f)
        assertFalse(empty.wide); assertEquals(0f, empty.mainWidth)
    }
}
