package io.github.ilyasyoy.voicedictation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.PlaybackState
import io.github.ilyasyoy.voicedictation.session.RecordingRecoveryState
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VocabularyPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tabsInputCaretAndInlineEditorSurvivePanelRelocationAndSavedState() {
        val width = mutableStateOf(700.dp)
        val dark = mutableStateOf(true)
        lateinit var ui: HistoryUiState
        val state = mutableStateOf(VocabularyState(listOf("Neovim", "Taskwarrior", "Ильяс"), true))
        val restore = StateRestorationTester(compose)
        var historyStops = 0
        restore.setContent { CompositionLocalProvider(LocalDensity provides Density(.5f, 1f)) {
            CompositionLocalProvider(LocalConfiguration provides Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }) { VoiceDictationTheme {
                ui = rememberHistoryUiState()
                Box(Modifier.requiredSize(width.value, 600.dp)) {
                    AdaptiveHistoryLayout(main = {}, history = { _, _, _, _ -> Surface(Modifier.fillMaxSize()) {
                        HistoryPanel(ui, { historyStops++ }, Modifier.fillMaxSize(), words = { modifier ->
                            VocabularyContent(state.value, false, null, ui,
                                onSave = { original, text, success -> state.value = state.value.copy(words = if (original == null)
                                    state.value.words + text else state.value.words.map { if (it == original) text else it }); success() },
                                onDelete = { word -> state.value = state.value.copy(words = state.value.words - word) }, onReload = {}, modifier = modifier)
                        }, history = { modifier -> Box(modifier) {
                            RecordingHistory(RecordingRecoveryState(), PlaybackState(), { false }, {}, {}, {}, {}, {}, ui = ui)
                        } })
                    } })
                }
            } }
        } }
        compose.onNodeWithTag("words_add_input").assertDoesNotExist()
        compose.onNodeWithTag("words_tab").performClick()
        compose.onNodeWithTag("words_add_input").performTextInput("unfinished phrase")
        compose.runOnIdle { width.value = 400.dp }
        compose.onNodeWithTag("words_add_input").assertTextContains("unfinished phrase")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("words_tab").assertIsSelected()
        compose.onNodeWithTag("words_add_input").assertTextContains("unfinished phrase")
        compose.runOnIdle { assertEquals(ui.wordInput.text.length, ui.wordInput.selection.end); dark.value = false }
        compose.onNodeWithTag("words_add").performClick()
        compose.onNodeWithTag("words_add_input").assertTextContains("")
        compose.onNodeWithTag("word_menu_Ильяс").performClick()
        compose.onNodeWithTag("word_edit_Ильяс").performClick()
        compose.onNodeWithTag("words_edit_input").performTextReplacement("Ильяс новый")
        compose.runOnIdle { width.value = 700.dp }
        compose.onNodeWithTag("words_edit_input").assertTextContains("Ильяс новый")
        compose.onNodeWithTag("words_save").performClick()
        compose.onNodeWithTag("word_menu_Ильяс новый").performClick()
        compose.onNodeWithTag("word_delete_Ильяс новый").performClick()
        compose.onNodeWithTag("word_menu_Ильяс новый").assertDoesNotExist()
        compose.onNodeWithTag("history_tab").performClick()
        compose.onNodeWithTag("history_content").assertExists()
        compose.runOnIdle { assertEquals(1, historyStops); assertFalse(ui.wordsSelected) }
    }
    @Test fun largeTextKeepsInputAndActionsReachableAndShowsHonestValidation() {
        var issue by mutableStateOf<VocabularyIssue?>(null)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.5f, 2f)) { VoiceDictationTheme {
            val ui = rememberHistoryUiState()
            Box(Modifier.requiredSize(320.dp, 650.dp)) {
                VocabularyContent(VocabularyState(listOf("Neovim"), true), false, issue, ui,
                    onSave = { _, _, _ -> issue = VocabularyIssue.DUPLICATE }, onDelete = {}, onReload = {})
            }
        } } }
        compose.onNodeWithTag("words_add_input").performTextInput("NEOVIM")
        compose.onNodeWithTag("words_add").assertIsDisplayed().performClick()
        compose.onNodeWithTag("words_error").assertIsDisplayed()
        compose.onNodeWithTag("words_add_input").assertTextContains("NEOVIM")
    }
    @Test fun wordNamedNoticeCanBeAddedAndEdited() {
        var state by mutableStateOf(VocabularyState(listOf("other"), true))
        compose.setContent { VoiceDictationTheme {
            val ui = rememberHistoryUiState()
            VocabularyContent(state, false, null, ui, onSave = { original, text, success ->
                state = state.copy(words = if (original == null) state.words + text else state.words.map { if (it == original) text else it })
                success()
            }, onDelete = { word -> state = state.copy(words = state.words - word) }, onReload = {})
        } }
        compose.onNodeWithTag("words_add_input").performTextInput("notice")
        compose.onNodeWithTag("words_add").performClick()
        compose.onNodeWithTag("word_menu_notice").assertExists().performClick()
        compose.onNodeWithTag("word_delete_notice").performClick()
        compose.onNodeWithTag("word_menu_other").performClick()
        compose.onNodeWithTag("word_edit_other").performClick()
        compose.onNodeWithTag("words_edit_input").performTextReplacement("notice")
        compose.onNodeWithTag("words_save").performClick()
        compose.onNodeWithTag("word_menu_notice").assertExists()
    }
}
