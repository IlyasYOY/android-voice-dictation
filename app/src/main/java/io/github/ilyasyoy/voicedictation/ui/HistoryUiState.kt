package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Presentation state survives panel relocation. Includes unfinished vocabulary text, never credentials. */
internal class HistoryUiState(val list: LazyListState = LazyListState(), val wordList: LazyListState = LazyListState()) {
    val expanded = mutableStateListOf<String>()
    var draftExpanded by mutableStateOf(false)
    var deleteId by mutableStateOf<String?>(null)
    var menuId by mutableStateOf<String?>(null)
    var limitOpen by mutableStateOf(false)
    var wordsSelected by mutableStateOf(false)
    var wordInput by mutableStateOf(TextFieldValue())
    var editingWord by mutableStateOf<String?>(null)
    var wordEdit by mutableStateOf(TextFieldValue())
    var wordMenu by mutableStateOf<String?>(null)
    var wordFocus by mutableStateOf<String?>(null)
    fun toggle(id: String) { if (!expanded.remove(id)) expanded.add(id) }
    companion object {
        val Saver = listSaver<HistoryUiState, Any>(
            save = { listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset,
                it.draftExpanded, it.deleteId.orEmpty(), it.menuId.orEmpty(), it.limitOpen,
                it.wordsSelected, it.wordInput.text, it.wordInput.selection.start, it.wordInput.selection.end,
                it.editingWord.orEmpty(), it.wordEdit.text, it.wordEdit.selection.start, it.wordEdit.selection.end,
                it.wordList.firstVisibleItemIndex, it.wordList.firstVisibleItemScrollOffset, it.wordFocus.orEmpty()) + it.expanded.toList() },
            restore = { values -> HistoryUiState(LazyListState(values[0] as Int, values[1] as Int),
                LazyListState(values[14] as Int, values[15] as Int)).apply {
                draftExpanded = values[2] as Boolean; deleteId = (values[3] as String).ifEmpty { null }
                menuId = (values[4] as String).ifEmpty { null }; limitOpen = values[5] as Boolean
                wordsSelected = values[6] as Boolean
                wordInput = TextFieldValue(values[7] as String, TextRange(values[8] as Int, values[9] as Int))
                editingWord = (values[10] as String).ifEmpty { null }
                wordEdit = TextFieldValue(values[11] as String, TextRange(values[12] as Int, values[13] as Int))
                wordFocus = (values[16] as String).ifEmpty { null }
                expanded.addAll(values.drop(17).map { it as String })
            } })
    }
}
@Composable internal fun rememberHistoryUiState() = rememberSaveable(saver = HistoryUiState.Saver) { HistoryUiState() }
