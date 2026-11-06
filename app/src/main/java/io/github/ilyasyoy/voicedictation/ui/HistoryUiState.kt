package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Presentation state survives panel relocation. Includes unfinished vocabulary text, never credentials. */
internal class HistoryUiState(val list: LazyListState = LazyListState(), val wordList: LazyListState = LazyListState()) {
    val expanded = mutableStateListOf<String>()
    val wordFormScroll = ScrollState(0)
    var draftExpanded by mutableStateOf(false)
    var deleteId by mutableStateOf<String?>(null)
    var limitOpen by mutableStateOf(false)
    var wordsSelected by mutableStateOf(false)
    var wordInput by mutableStateOf(TextFieldValue())
    var editingWord by mutableStateOf<String?>(null)
    var wordEdit by mutableStateOf(TextFieldValue())
    var wordMenu by mutableStateOf<String?>(null)
    var wordFocus by mutableStateOf<String?>(null)
    fun toggle(id: String) { if (!expanded.remove(id)) expanded.add(id) }
    fun closeTransient() {
        deleteId = null; limitOpen = false; wordMenu = null; wordFocus = null
    }
    companion object {
        val Saver = listSaver<HistoryUiState, Any>(
            save = { listOf("history-ui-v4", it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset,
                it.draftExpanded, it.deleteId.orEmpty(), "", it.limitOpen, // Preserve the obsolete menu slot for older saved states.
                it.wordsSelected, it.wordInput.text, it.wordInput.selection.start, it.wordInput.selection.end,
                it.editingWord.orEmpty(), it.wordEdit.text, it.wordEdit.selection.start, it.wordEdit.selection.end,
                it.wordList.firstVisibleItemIndex, it.wordList.firstVisibleItemScrollOffset, it.wordFocus.orEmpty()) + it.expanded.toList() },
            restore = ::restoreSaved)

        /** Old installed versions saved six header fields followed by expanded IDs, without a version. */
        internal fun restoreSaved(saved: List<Any>): HistoryUiState {
            val processing = saved.firstOrNull() == "history-ui-v3"
            val versioned = processing || saved.firstOrNull() in listOf("history-ui-v2", "history-ui-v4")
            if (!versioned && saved.firstOrNull() !is Int) return HistoryUiState()
            val values = if (versioned) saved.drop(1) else saved
            // Recognize the unreleased initial Words shape too; never cast an expanded legacy ID to Boolean.
            val words = versioned || (values.size >= 17 && values.getOrNull(6) is Boolean)
            fun position(index: Int) = (values.getOrNull(index) as? Int)?.coerceAtLeast(0) ?: 0
            fun text(index: Int) = values.getOrNull(index) as? String ?: ""
            fun input(textIndex: Int, startIndex: Int, endIndex: Int): TextFieldValue {
                val value = text(textIndex)
                return TextFieldValue(value, TextRange(position(startIndex).coerceAtMost(value.length), position(endIndex).coerceAtMost(value.length)))
            }
            return HistoryUiState(LazyListState(position(0), position(1)),
                if (words) LazyListState(position(14), position(15)) else LazyListState()).apply {
                draftExpanded = values.getOrNull(2) as? Boolean ?: false
                deleteId = text(3).ifEmpty { null }
                limitOpen = values.getOrNull(5) as? Boolean ?: false
                if (words) {
                    wordsSelected = values.getOrNull(6) as? Boolean ?: false
                    wordInput = input(7, 8, 9); editingWord = text(10).ifEmpty { null }
                    wordEdit = input(11, 12, 13); wordFocus = text(16).takeIf { it == "add" || it == "edit" }
                }
                // v3's obsolete repeat editor is intentionally dismissed; keep the lists and edits.
                expanded.addAll(values.drop(if (processing) 20 else if (words) 17 else 6).filterIsInstance<String>())
            }
        }
    }
}
@Composable internal fun rememberHistoryUiState() = rememberSaveable(saver = HistoryUiState.Saver) { HistoryUiState() }
