package io.github.ilyasyoy.voicedictation.ui

import androidx.lifecycle.SavedStateHandle
import io.github.ilyasyoy.voicedictation.data.ExampleTranscriptRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationViewModelTest {
    private val examples = ExampleTranscriptRepository { "Clearly labeled sample" }

    @Test fun `initial state is empty and not recording`() {
        val model = DictationViewModel(SavedStateHandle(), examples)
        assertEquals("", model.draft.value.text)
        assertFalse(model.draft.value.isExample)
    }

    @Test fun `load example uses injected local source`() {
        val model = DictationViewModel(SavedStateHandle(), examples)
        model.loadExample()
        assertEquals("Clearly labeled sample", model.draft.value.text)
        assertTrue(model.draft.value.isExample)
    }

    @Test fun `repeated example and clear actions are safe`() {
        val model = DictationViewModel(SavedStateHandle(), examples)
        repeat(3) { model.loadExample() }
        assertEquals("Clearly labeled sample", model.draft.value.text)
        repeat(3) { model.clear() }
        assertEquals("", model.draft.value.text)
        assertFalse(model.draft.value.isExample)
    }

    @Test fun `editing sample removes example label`() {
        val model = DictationViewModel(SavedStateHandle(), examples)
        model.loadExample()
        model.edit("My own note")
        assertFalse(model.draft.value.isExample)
        assertEquals("My own note", model.draft.value.text)
    }

    @Test fun `saved state restores draft and origin`() {
        val saved = SavedStateHandle()
        DictationViewModel(saved, examples).loadExample()
        val restored = DictationViewModel(saved, examples)
        assertEquals("Clearly labeled sample", restored.draft.value.text)
        assertTrue(restored.draft.value.isExample)
    }

    @Test fun `clear updates saved state too`() {
        val saved = SavedStateHandle()
        val model = DictationViewModel(saved, examples)
        model.edit("Temporary")
        model.clear()
        assertEquals("", DictationViewModel(saved, examples).draft.value.text)
    }
}
