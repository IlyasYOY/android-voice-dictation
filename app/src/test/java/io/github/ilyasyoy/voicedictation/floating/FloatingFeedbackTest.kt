package io.github.ilyasyoy.voicedictation.floating

import org.junit.Assert.*
import org.junit.Test

class FloatingFeedbackTest {
    @Test fun unverifiedInsertionSettlesSilentlyWithoutRetainingOrReplayingPossiblyInsertedText() {
        val feedback=FloatingFeedback()
        repeat(3) {
            assertTrue(feedback.complete(InsertionResult.UNCONFIRMED,"Possibly inserted","Copy required","Unconfirmed"))
            assertFalse(feedback.available); assertFalse(feedback.expanded); assertFalse(feedback.error)
            assertEquals("",feedback.text); assertNull(feedback.message)
        }
    }
    @Test fun genuinelyUnsentTextSurvivesNewSessionsAndSilentSuccessUntilExplicitDiscard() {
        val feedback=FloatingFeedback()
        assertFalse(feedback.complete(InsertionResult.COPY,"Unsent words","Copy required","Unconfirmed"))
        feedback.resetSignal() // Starting again must not erase optional recovery.
        assertEquals("Unsent words",feedback.text); assertNull(feedback.message)
        for(result in listOf(InsertionResult.INSERTED,InsertionResult.UNCONFIRMED)) {
            assertTrue(feedback.complete(result,"New words","Copy required","Unconfirmed"))
            assertEquals("Unsent words",feedback.text); assertFalse(feedback.error)
        }
        feedback.offer("Another unsent result","Network failed",error=true)
        assertEquals("Unsent words\n\nAnother unsent result",feedback.text)
        feedback.discard(); assertFalse(feedback.available)
    }
    @Test fun realFailureIsRedAndRetryClearsOnlyTheSignal() {
        val feedback=FloatingFeedback()
        feedback.offer("Partial words","Network error",error=true)
        assertTrue(feedback.error); assertFalse(feedback.expanded)
        feedback.resetSignal()
        assertFalse(feedback.error); assertNull(feedback.message)
        assertEquals("Partial words",feedback.text)
        feedback.dismiss(); assertEquals("Partial words",feedback.text)
    }
}
