package io.github.ilyasyoy.voicedictation.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDraftTest {
    @Test fun `empty draft cannot be cleared`() {
        assertFalse(TranscriptDraft().canClear)
    }

    @Test fun `editing an example changes its origin`() {
        val draft = TranscriptDraft("example", isExample = true).edit("my words")
        assertEquals("my words", draft.text)
        assertFalse(draft.isExample)
        assertTrue(draft.canClear)
    }

    @Test fun `clear resets text and origin`() {
        assertEquals(TranscriptDraft(), TranscriptDraft("example", true).clear())
    }

    @Test fun `clear is idempotent`() {
        assertEquals(TranscriptDraft(), TranscriptDraft().clear().clear())
    }

    @Test fun `editing preserves unicode and line breaks`() {
        val text = "Мысль 🎙️\nAnother thought"
        assertEquals(text, TranscriptDraft().edit(text).text)
    }
}
