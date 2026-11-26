package io.github.ilyasyoy.voicedictation.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingKeyboardOpeningTest {
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
            if (opening.canShow(geometry, time, alreadyShowing = false)) {
                checkNotNull(FloatingArea.create(360, 800, geometry.top, 1f)).place(saved)
            } else null
        }
        assertEquals(listOf(FloatingPoint(78, 312)), visible)
    }

    @Test fun reopenCannotReuseThePreviousKeyboardSample() {
        val opening = FloatingKeyboardOpening()
        assertFalse(opening.canShow(keyboard, 0, false))
        assertTrue(opening.canShow(keyboard, 120, false))
        opening.reset()
        assertFalse(opening.canShow(keyboard, 1_000, false))
        assertTrue(opening.canShow(keyboard, 1_120, false))
    }

    @Test fun keyboardReplacementAndEmptyBoundsRestartTheOpeningCheck() {
        val opening = FloatingKeyboardOpening()
        val replacement = keyboard.copy(windowId = 8)
        assertFalse(opening.canShow(keyboard, 0, false))
        assertFalse(opening.canShow(replacement, 120, false))
        assertFalse(opening.canShow(replacement.copy(right = 0), 240, false))
        assertFalse(opening.canShow(replacement, 280, false))
        assertTrue(opening.canShow(replacement, 400, false))
    }

    @Test fun attachedBubbleStillFollowsKeyboardHeightChangesImmediately() {
        val opening = FloatingKeyboardOpening()
        assertTrue(opening.canShow(keyboard, 0, alreadyShowing = true))
        assertTrue(opening.canShow(keyboard.copy(top = 520), 40, alreadyShowing = true))
    }
}
