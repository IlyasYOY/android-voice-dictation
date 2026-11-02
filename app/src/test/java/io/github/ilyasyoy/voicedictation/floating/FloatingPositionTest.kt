package io.github.ilyasyoy.voicedictation.floating

import org.junit.Assert.*
import org.junit.Test

class FloatingPositionTest {
    private class Store(var encoded: String? = null, var succeeds: Boolean = true) : FloatingPositionStore {
        var writes = 0
        override fun load() = encoded
        override fun save(value: String): Boolean { writes++; if (succeeds) encoded = value; return succeeds }
    }
    private fun area(width: Int = 360, height: Int = 800, ime: Int = 600, density: Float = 1f) =
        checkNotNull(FloatingArea.create(width, height, ime, density))

    @Test fun absentPreferenceUsesOriginalRightAboveKeyboardDefaultWithoutWriting() {
        val store = Store(); val state = FloatingPositionState(store)
        assertEquals(FloatingPoint(288, 528), state.place(area()))
        assertEquals(0, store.writes)
    }
    @Test fun finishedDragSurvivesNewStateAndDurableStoreRoundTrip() {
        val store = Store(); val chosen = FloatingPoint(100, 300)
        assertTrue(FloatingPositionState(store).dragged(chosen, area()))
        val restartedStore = Store(store.encoded)
        assertEquals(chosen, FloatingPositionState(restartedStore).place(area()))
        assertEquals(1, store.writes); assertEquals(0, restartedStore.writes)
    }
    @Test fun keyboardHeightChangesPreserveGapInsteadOfDefaultingOrStickingUnderIme() {
        val store = Store(); val state = FloatingPositionState(store)
        state.dragged(FloatingPoint(100, 300), area())
        assertEquals(FloatingPoint(100, 200), state.place(area(ime = 500)))
        assertEquals(FloatingPoint(100, 400), state.place(area(ime = 700)))
        assertEquals(1, store.writes)
    }
    @Test fun temporaryLandscapeClampingDoesNotOverwritePortraitIntent() {
        val store = Store(); val state = FloatingPositionState(store)
        val chosen = FloatingPoint(140, 180); state.dragged(chosen, area())
        val encoded = store.encoded
        val cramped = area(800, 360, 260)
        assertEquals(cramped.top, state.place(cramped).y)
        assertEquals(chosen, state.place(area()))
        assertEquals(encoded, store.encoded); assertEquals(1, store.writes)
        assertEquals(chosen, FloatingPositionState(Store(encoded)).place(area()))
    }
    @Test fun densityChangesPreserveDpGapAndHorizontalFraction() {
        val state = FloatingPositionState(Store())
        state.dragged(FloatingPoint(100, 300), area())
        assertEquals(FloatingPoint(200, 600), state.place(area(720, 1600, 1200, 2f)))
    }
    @Test fun changedScreenWidthMapsHorizontalIntentProportionally() {
        val state = FloatingPositionState(Store())
        state.dragged(FloatingPoint(148, 300), area()) // Halfway along 280px travel.
        assertEquals(328, state.place(area(width = 720)).x)
    }
    @Test fun sideCutoutsStatusAndNavigationInsetsRemainOutsideCircleArea() {
        val safe = checkNotNull(FloatingArea.create(500, 800, 790, 1f, 40, 52, 16, 48))
        assertEquals(48, safe.left); assertEquals(52, safe.top)
        assertEquals(476, safe.right); assertEquals(744, safe.bottom)
        assertEquals(FloatingPoint(412, 680), safe.place(FloatingPosition.DEFAULT))
        val high = safe.place(FloatingPosition(.5f, 5000f))
        assertEquals(52, high.y); assertTrue(high.x >= safe.left && high.x + safe.diameter <= safe.right)
    }
    @Test fun tooLittleSpaceHidesWithoutMutatingSavedPreference() {
        val store = Store("1|0.3|150.0"); val state = FloatingPositionState(store)
        assertNull(FloatingArea.create(70, 800, 600, 1f))
        assertNull(FloatingArea.create(360, 800, 80, 1f))
        assertEquals(0, store.writes); assertEquals("1|0.3|150.0", store.encoded)
        assertTrue(state.place(area()).x > area().left)
    }
    @Test fun invalidLegacyAndNonFinitePreferencesFallBackWithoutRewritingOtherSettings() {
        for (encoded in listOf(null, "", "120,240", "0|0.2|80", "2|0.2|80", "1|NaN|0", "1|Infinity|0",
            "1|0.5|NaN", "1|0.5|Infinity", "1|-0.1|0", "1|1.01|0", "1|0.5|-1", "1|0.5", "1|0.5|0|extra")) {
            val store = Store(encoded)
            assertEquals(encoded, FloatingPoint(288, 528), FloatingPositionState(store).place(area()))
            assertEquals(0, store.writes); assertEquals(encoded, store.encoded)
        }
    }
    @Test fun finiteVeryLargeLegacyGapIsSafelyClampedWithoutIntegerOverflow() {
        val store = Store("1|1.0|${Float.MAX_VALUE}")
        assertEquals(FloatingPoint(288, 24), FloatingPositionState(store).place(area()))
        assertEquals(0, store.writes)
    }
    @Test fun releasingOutsideSafeBoundsStoresOnlyReachableCircleCoordinates() {
        val store = Store(); val state = FloatingPositionState(store)
        state.dragged(FloatingPoint(Int.MIN_VALUE, Int.MAX_VALUE), area())
        assertEquals(FloatingPoint(8, 528), state.place(area()))
        assertEquals(state.place(area()), FloatingPositionState(Store(store.encoded)).place(area()))
    }
    @Test fun failedDiskCommitKeepsCurrentSessionPlacementWithoutClaimingDurability() {
        val original = "1|1.0|0.0"; val store = Store(original, succeeds = false)
        val state = FloatingPositionState(store); val chosen = FloatingPoint(100, 300)
        assertFalse(state.dragged(chosen, area()))
        assertEquals(chosen, state.place(area())); assertEquals(original, store.encoded)
        assertEquals(FloatingPoint(288, 528), FloatingPositionState(store).place(area()))
    }
    @Test fun malformedDisplayGeometryNeverProducesAnOffscreenWindow() {
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE))
            assertNull(FloatingArea.create(360, 800, 600, density))
        assertNull(FloatingArea.create(0, 800, 600, 1f))
        assertNull(FloatingArea.create(360, -1, 600, 1f))
        assertNull(FloatingArea.create(360, 800, -1, 1f))
    }
}
