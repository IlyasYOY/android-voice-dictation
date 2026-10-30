package io.github.ilyasyoy.voicedictation.floating

import org.junit.Assert.*
import org.junit.Test

class EditorPolicyTest {
    private fun editor() = EditorToken("fixture.notes", 7, "body", 4, 6, 6, false, false, 1, 0, -1, -1)
    @Test fun `normal text editor may show bubble and insert only into stable caret`() {
        val e = editor()
        assertTrue(EditorPolicy.allowed(e)); assertTrue(EditorPolicy.canInsert(e, e, 36, "voice"))
        assertFalse(EditorPolicy.canInsert(e, e.copy(session = 5), 36, "voice"))
        assertFalse(EditorPolicy.canInsert(e, e.copy(selectionStart = 7, selectionEnd = 7), 36, "voice"))
        assertFalse(EditorPolicy.canInsert(e, e.copy(packageName = "other.notes"), 36, "voice"))
        assertFalse(EditorPolicy.canInsert(e, e.copy(windowId = 9), 36, "voice"))
    }
    @Test fun `password numeric security sensitive and unknown editors are excluded`() {
        val e = editor()
        assertFalse(EditorPolicy.allowed(e.copy(password = true)))
        assertFalse(EditorPolicy.allowed(e.copy(sensitive = true)))
        assertFalse(EditorPolicy.allowed(e.copy(inputType = 129))) // Text/password.
        assertFalse(EditorPolicy.allowed(e.copy(inputType = 2))) // Numeric/payment/OTP.
        assertFalse(EditorPolicy.allowed(e.copy(inputType = 0)))
        assertFalse(EditorPolicy.allowed(e.copy(viewId = "credit_card")))
        assertFalse(EditorPolicy.allowed(e.copy(packageName = "example.authenticator")))
        assertFalse(EditorPolicy.allowed(e.copy(imeOptions = 0x1000000))) // No personalized learning.
    }
    @Test fun `selection composition old Android and long output require copy fallback`() {
        val e = editor()
        assertFalse(EditorPolicy.canInsert(e, e.copy(selectionEnd = 9), 36, "voice"))
        assertFalse(EditorPolicy.canInsert(e.copy(composingStart = 1, composingEnd = 6), e, 36, "voice"))
        assertFalse(EditorPolicy.canInsert(e, e, 28, "voice"))
        assertFalse(EditorPolicy.canInsert(e, e, 36, ""))
        assertFalse(EditorPolicy.canInsert(e, e, 36, "x".repeat(10_001)))
    }
    @Test fun `insertion confirmation requires expected caret and dictated suffix`() {
        assertTrue(EditorPolicy.confirmed(6, "voice", 11, 11, "voice"))
        assertFalse(EditorPolicy.confirmed(6, "voice", 6, 6, "old"))
        assertFalse(EditorPolicy.confirmed(6, "voice", 11, 12, "voice"))
        assertFalse(EditorPolicy.confirmed(6, "voice", 11, 11, "other"))
    }
}
