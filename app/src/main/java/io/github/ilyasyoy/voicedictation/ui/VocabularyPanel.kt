package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*

@Composable
internal fun VocabularyPanel(ui: HistoryUiState, modifier: Modifier = Modifier) {
    val model: VocabularyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val issue by model.issue.collectAsStateWithLifecycle()
    VocabularyContent(state, busy, issue, ui, model::save, model::delete, model::reload, modifier, model::clearIssue)
}

/** Same panel in the persistent pane and modal sheet. Rendering never starts capture or a request. */
@Composable
internal fun VocabularyContent(state: VocabularyState, busy: Boolean, issue: VocabularyIssue?, ui: HistoryUiState,
    onSave: (String?, String, () -> Unit) -> Unit, onDelete: (String) -> Unit, onReload: () -> Unit,
    modifier: Modifier = Modifier, onClearIssue: () -> Unit = {}) {
    val focus = LocalFocusManager.current
    val submit = {
        val submitted = ui.wordInput.text
        onSave(null, submitted) { if (ui.wordInput.text == submitted) ui.wordInput = TextFieldValue() }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("words_content")) {
        WordEntry(ui.wordInput, { ui.wordInput = it; onClearIssue() }, submit,
            stringResource(R.string.words_new), stringResource(R.string.words_add), "words_add_input", "words_add",
            enabled = state.readable, restoreFocus = ui.wordFocus == "add", onFocus = { ui.wordFocus = "add" }, canSubmit = !busy)
        val error = if (!state.readable && !busy) VocabularyIssue.STORAGE else issue
        if (error != null) Text(stringResource(when (error) {
            VocabularyIssue.EMPTY -> R.string.words_empty_error
            VocabularyIssue.INVALID -> R.string.words_invalid
            VocabularyIssue.DUPLICATE -> R.string.words_duplicate
            VocabularyIssue.CAPACITY -> R.string.words_capacity
            VocabularyIssue.MISSING -> R.string.words_missing
            VocabularyIssue.STORAGE -> R.string.words_storage
        }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp).testTag("words_error").semantics { liveRegion = LiveRegionMode.Polite })
        if (!state.readable || issue == VocabularyIssue.STORAGE) TextButton(onReload,
            enabled = !busy, modifier = Modifier.testTag("words_reload")) { Text(stringResource(R.string.words_reload)) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("words_list"), state = ui.wordList,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.readable && state.words.isEmpty()) item("header:empty") {
                Text(stringResource(R.string.words_empty), Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(state.words, key = { "word:$it" }) { word ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(word, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
                            Box {
                                val label = stringResource(R.string.words_options, word)
                                IconButton({ ui.wordMenu = word }, enabled = !busy,
                                    modifier = Modifier.size(48.dp).testTag("word_menu_$word").semantics { contentDescription = label }) {
                                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                                    Canvas(Modifier.size(20.dp)) { for (i in -1..1) drawCircle(color, 1.5.dp.toPx(), center.copy(y = center.y + i * 6.dp.toPx())) }
                                }
                                DropdownMenu(ui.wordMenu == word, { ui.wordMenu = null }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.words_edit)) },
                                        onClick = {
                                            ui.wordMenu = null; ui.editingWord = word
                                            ui.wordEdit = TextFieldValue(word, TextRange(word.length)); ui.wordFocus = "edit"
                                            onClearIssue()
                                        }, modifier = Modifier.testTag("word_edit_$word"))
                                    DropdownMenuItem(text = { Text(stringResource(R.string.words_delete), color = MaterialTheme.colorScheme.error) },
                                        onClick = { ui.wordMenu = null; if (ui.editingWord == word) { ui.editingWord = null; ui.wordFocus = null; focus.clearFocus() }; onDelete(word) },
                                        modifier = Modifier.testTag("word_delete_$word"))
                                }
                            }
                        }
                        if (ui.editingWord == word) {
                            val save = {
                                val submitted = ui.wordEdit.text
                                onSave(word, submitted) {
                                    if (ui.editingWord == word && ui.wordEdit.text == submitted) {
                                        ui.editingWord = null; ui.wordFocus = null; focus.clearFocus()
                                    }
                                }
                            }
                            WordEntry(ui.wordEdit, { ui.wordEdit = it; onClearIssue() }, save,
                                stringResource(R.string.words_edit), stringResource(R.string.words_save), "words_edit_input", "words_save",
                                enabled = !busy && state.readable, restoreFocus = ui.wordFocus == "edit", onFocus = { ui.wordFocus = "edit" })
                            TextButton({ ui.editingWord = null; ui.wordFocus = null; focus.clearFocus(); onClearIssue() }, enabled = !busy) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    }
                }
            }
            item("footer:notice") { Text(stringResource(R.string.words_notice), Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WordEntry(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, onSubmit: () -> Unit,
    label: String, action: String, inputTag: String, actionTag: String, enabled: Boolean,
    restoreFocus: Boolean, onFocus: () -> Unit, canSubmit: Boolean = true) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (restoreFocus && enabled) requester.requestFocus() }
    val field: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(value, onValue, enabled = enabled, singleLine = true, label = { Text(label) },
            shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (enabled && canSubmit) onSubmit() }),
            modifier = modifier.testTag(inputTag).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocus() })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (LocalDensity.current.fontScale > 1.3f || maxWidth < 240.dp) Column {
            field(Modifier.fillMaxWidth())
            Button(onSubmit, enabled = enabled && canSubmit && value.text.isNotBlank(), modifier = Modifier.align(Alignment.End).testTag(actionTag)) { Text(action) }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Modifier.weight(1f))
            Button(onSubmit, enabled = enabled && canSubmit && value.text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag(actionTag)) { Text(action) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryPanel(ui: HistoryUiState, onWordsSelected: () -> Unit, modifier: Modifier = Modifier,
    words: @Composable (Modifier) -> Unit, history: @Composable (Modifier) -> Unit) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("history_tabs")) {
            listOf(R.string.history_title, R.string.words_title).forEachIndexed { index, label ->
                SegmentedButton(selected = ui.wordsSelected == (index == 1), onClick = {
                    ui.wordFocus = null; focus.clearFocus(); ui.wordsSelected = index == 1
                    if (index == 1) onWordsSelected()
                }, shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {},
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (index == 0) "history_tab" else "words_tab")) { Text(stringResource(label)) }
            }
        }
        if (ui.wordsSelected) words(Modifier.weight(1f)) else history(Modifier.weight(1f))
    }
}
