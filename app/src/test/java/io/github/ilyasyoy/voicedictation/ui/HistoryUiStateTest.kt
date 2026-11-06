package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class HistoryUiStateTest {
    @Test fun `collapse clears only transient actions while retaining both lists and unfinished Words`() {
        val state = HistoryUiState(androidx.compose.foundation.lazy.LazyListState(3, 7), androidx.compose.foundation.lazy.LazyListState(4, 9)).apply {
            wordsSelected = true; wordInput = TextFieldValue("unfinished", TextRange(3)); editingWord = "original"
            wordEdit = TextFieldValue("edit draft"); wordFocus = "edit"; expanded.add("message")
            deleteId = "message"; menuId = "message"; wordMenu = "original"; limitOpen = true
        }
        state.closeTransient()
        assertTrue(state.wordsSelected); assertEquals("unfinished", state.wordInput.text); assertEquals(TextRange(3), state.wordInput.selection)
        assertEquals("edit draft", state.wordEdit.text); assertEquals("original", state.editingWord)
        assertEquals(listOf("message"), state.expanded.toList()); assertEquals(3, state.list.firstVisibleItemIndex)
        assertEquals(4, state.wordList.firstVisibleItemIndex)
        assertNull(state.deleteId); assertNull(state.menuId); assertNull(state.wordMenu); assertNull(state.wordFocus); assertFalse(state.limitOpen)
    }
    @Test fun `old empty and expanded history states restore safely without Words`() {
        val empty = HistoryUiState.restoreSaved(listOf(0, 0, false, "", "", false))
        assertFalse(empty.wordsSelected); assertTrue(empty.expanded.isEmpty())
        val expanded = HistoryUiState.restoreSaved(listOf(4, 32, true, "delete", "menu", true, "first", "second"))
        assertEquals(4, expanded.list.firstVisibleItemIndex); assertEquals(32, expanded.list.firstVisibleItemScrollOffset)
        assertEquals(listOf("first", "second"), expanded.expanded.toList())
        assertTrue(expanded.draftExpanded); assertEquals("delete", expanded.deleteId); assertTrue(expanded.limitOpen)
        assertFalse(expanded.wordsSelected); assertEquals("", expanded.wordInput.text)
    }
    @Test fun `versioned save restores both scrolls tab caret and unfinished inline editing`() {
        val state = HistoryUiState(androidx.compose.foundation.lazy.LazyListState(2, 12), androidx.compose.foundation.lazy.LazyListState(3, 24)).apply {
            wordsSelected = true; wordInput = TextFieldValue("unfinished", TextRange(2, 5))
            editingWord = "Ильяс"; wordEdit = TextFieldValue("Ильяс новый", TextRange(4)); wordFocus = "edit"
            expanded.add("retained-message")
        }
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(HistoryUiState.Saver) { scope.save(state) }!!
        val restored = HistoryUiState.Saver.restore(saved)!!
        assertTrue(restored.wordsSelected); assertEquals(TextRange(2, 5), restored.wordInput.selection)
        assertEquals("unfinished", restored.wordInput.text); assertEquals("Ильяс новый", restored.wordEdit.text)
        assertEquals("Ильяс", restored.editingWord); assertEquals("edit", restored.wordFocus)
        assertEquals(3, restored.wordList.firstVisibleItemIndex); assertEquals(24, restored.wordList.firstVisibleItemScrollOffset)
        assertEquals(2, restored.list.firstVisibleItemIndex); assertEquals(listOf("retained-message"), restored.expanded.toList())
    }
    @Test fun `truncated future and malformed shapes never crash restore`() {
        for (values in listOf(emptyList(), listOf("future-v3"), listOf(0), listOf(-4, -5, "bad", 8, false, "bad"),
            listOf("history-ui-v2", 0, 0, false, "", "", false, true, "x", 999, -9))) {
            val restored = HistoryUiState.restoreSaved(values)
            assertTrue(restored.list.firstVisibleItemIndex >= 0); assertTrue(restored.list.firstVisibleItemScrollOffset >= 0)
            assertTrue(restored.wordInput.selection.end <= restored.wordInput.text.length)
        }
    }
    @Test fun `old repeat editor is dismissed while v3 list position and expansion survive`() {
        val old = listOf<Any>("history-ui-v3", 2, 7, false, "", "", false, false, "", 0, 0,
            "", "", 0, 0, 0, 0, "", "row", "gpt-4.1", "Old one-off prompt", "row")
        val restored = HistoryUiState.restoreSaved(old)
        assertEquals(2, restored.list.firstVisibleItemIndex)
        assertEquals(listOf("row"), restored.expanded.toList())
    }
}
