package io.github.ilyasyoy.voicedictation.floating

import org.junit.Assert.*
import org.junit.Test

class FloatingFeedbackTest {
    @Test fun readyOrUnconfirmedTextNeverLooksLikeSuccessfulInsertionAndSurvivesDismiss() {
        val feedback=FloatingFeedback()
        for (result in listOf(InsertionResult.COPY,InsertionResult.UNCONFIRMED)) {
            assertFalse(feedback.complete(result,"Unsaved final words","Copy required","Check field first"))
            assertTrue(feedback.available)
            assertFalse(feedback.expanded) // No automatic report.
            feedback.expanded=true
            assertEquals("Unsaved final words",feedback.text)
            feedback.dismiss()
            assertTrue(feedback.available)
            assertEquals("Unsaved final words",feedback.text)
            assertEquals(if (result==InsertionResult.COPY) "Copy required" else "Check field first",feedback.message)
        }
    }
    @Test fun onlyConfirmedInsertionReturnsToIdleWithoutAReport() {
        val feedback=FloatingFeedback()
        feedback.offer("Uninserted", "Copy required",true); feedback.expanded=true
        assertTrue(feedback.complete(InsertionResult.INSERTED,"Final words","Copy required","Unconfirmed"))
        assertFalse(feedback.available); assertFalse(feedback.expanded)
        assertEquals("",feedback.text); assertNull(feedback.message); assertFalse(feedback.needsSetup)
    }
    @Test fun errorAndCancellationRemainRecoverableUntilExplicitDiscard() {
        val feedback=FloatingFeedback()
        feedback.offer("Partial words","Network error")
        feedback.dismiss()
        assertEquals("Partial words",feedback.text)
        assertFalse(feedback.complete(InsertionResult.CANCELLED,"","",""))
        assertEquals("Partial words",feedback.text)
        feedback.discard()
        assertFalse(feedback.available)
    }
}
