package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Presentation-only state. Saved values contain identifiers/positions, never transcript or credentials. */
internal class HistoryUiState(val list: LazyListState = LazyListState()) {
    val expanded = mutableStateListOf<String>()
    var draftExpanded by mutableStateOf(false)
    var deleteId by mutableStateOf<String?>(null)
    var menuId by mutableStateOf<String?>(null)
    var limitOpen by mutableStateOf(false)
    fun toggle(id: String) { if (!expanded.remove(id)) expanded.add(id) }
    companion object {
        val Saver = listSaver<HistoryUiState, Any>(
            save = { listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset,
                it.draftExpanded, it.deleteId.orEmpty(), it.menuId.orEmpty(), it.limitOpen) + it.expanded.toList() },
            restore = { values -> HistoryUiState(LazyListState(values[0] as Int, values[1] as Int)).apply {
                draftExpanded = values[2] as Boolean; deleteId = (values[3] as String).ifEmpty { null }
                menuId = (values[4] as String).ifEmpty { null }; limitOpen = values[5] as Boolean
                expanded.addAll(values.drop(6).map { it as String })
            } })
    }
}
@Composable internal fun rememberHistoryUiState() = rememberSaveable(saver = HistoryUiState.Saver) { HistoryUiState() }
