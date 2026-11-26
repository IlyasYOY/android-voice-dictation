package io.github.ilyasyoy.voicedictation.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingKeyboardOpeningTest {
    private val display = FloatingDisplayHeight(0, 800)
    private val keyboard = FloatingKeyboardGeometry(7, 0, 480, 360, 800)

    @Test fun firstAttachUsesTheSettledKeyboardAndSavedAnchor() {
        val opening = FloatingKeyboardOpening()
        val frames = listOf(
            0L to keyboard.copy(top = 800),
            40L to keyboard.copy(top = 760),
            80L to keyboard.copy(top = 620),
            120L to keyboard,
            160L to keyboard,
            200L to keyboard,
            240L to keyboard,
        )
        val saved = FloatingPosition(.25f, 96f)
        val visible = frames.mapNotNull { (time, geometry) ->
            if (opening.canShow(geometry, time, alreadyShowing = false, display = display)) {
                checkNotNull(FloatingArea.create(360, 800, geometry.top, 1f)).place(saved)
            } else null
        }
        assertEquals(listOf(FloatingPoint(78, 312)), visible)
    }

    @Test fun reopenCannotReuseThePreviousKeyboardSample() {
        val opening = FloatingKeyboardOpening()
        assertFalse(opening.canShow(keyboard, 0, false, display = display))
        assertTrue(opening.canShow(keyboard, 120, false, display = display))
        opening.reset()
        assertFalse(opening.canShow(keyboard, 1_000, false, display = display))
        assertTrue(opening.canShow(keyboard, 1_120, false, display = display))
    }

    @Test fun keyboardReplacementAndEmptyBoundsRestartTheOpeningCheck() {
        val opening = FloatingKeyboardOpening()
        val replacement = keyboard.copy(windowId = 8)
        assertFalse(opening.canShow(keyboard, 0, false, display = display))
        assertFalse(opening.canShow(replacement, 120, false, display = display))
        assertFalse(opening.canShow(replacement.copy(right = 0), 240, false, display = display))
        assertFalse(opening.canShow(replacement, 280, false, display = display))
        assertTrue(opening.canShow(replacement, 400, false, display = display))
    }

    @Test fun rejectsStableOffscreenKeyboardSeenOnPhoneUntilOnscreenBoundsSettle() {
        val opening = FloatingKeyboardOpening()
        val phoneDisplay = FloatingDisplayHeight(0, 2520)
        val offscreen = FloatingKeyboardGeometry(7, 0, 2487, 1080, 3505)
        val final = offscreen.copy(top = 1502, bottom = 2520)
        // v0.19.7 accepted at 139 ms because the rectangle had not changed.
        for (time in listOf(0L, 46L, 92L, 139L, 400L, 510L)) {
            assertFalse(opening.canShow(offscreen, time, false, phoneDisplay))
        }
        for (time in listOf(511L, 551L, 591L, 630L)) {
            assertFalse(opening.canShow(final, time, false, phoneDisplay))
        }
        assertTrue(opening.canShow(final, 631L, false, phoneDisplay))
    }

    @Test fun partiallyAboveDisplayAlsoResetsAnOpeningSample() {
        val opening = FloatingKeyboardOpening()
        val offsetDisplay = FloatingDisplayHeight(100, 900)
        assertFalse(opening.canShow(keyboard, 0, false, offsetDisplay))
        assertFalse(opening.canShow(keyboard.copy(top = 99), 121, false, offsetDisplay))
        assertFalse(opening.canShow(keyboard, 122, false, offsetDisplay))
        assertTrue(opening.canShow(keyboard, 242, false, offsetDisplay))
    }

    @Test fun attachedBubbleStillFollowsKeyboardHeightChangesImmediately() {
        val opening = FloatingKeyboardOpening()
        assertTrue(opening.canShow(keyboard, 0, alreadyShowing = true, display = display))
        assertTrue(opening.canShow(keyboard.copy(top = 520), 40, alreadyShowing = true, display = null))
    }
}
