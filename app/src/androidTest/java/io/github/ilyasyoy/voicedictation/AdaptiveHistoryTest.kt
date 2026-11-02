package io.github.ilyasyoy.voicedictation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso.pressBack
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.RecordingRecoveryState
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic layout tests; compilation is reported separately from actual device execution. */
class AdaptiveHistoryTest {
    @get:Rule val compose = createComposeRule()
    private val row = SavedRecording("adaptive", 1700000000000, 1700000000000,
        SavedRecordingStatus.COMPLETE, 12, "Synthetic retained transcript")

    @Test fun sheetPaneResizePreservesExpansionDraftAndMainSettingsWithoutDuplicateContent() {
        val width = mutableStateOf(1000.dp)
        val restore = StateRestorationTester(compose)
        restore.setContent { VoiceDictationTheme {
            val controller: HistoryViewModel = viewModel()
            val ui = rememberHistoryUiState()
            var open by rememberSaveable { mutableStateOf(true) }
            Box(Modifier.requiredSize(width.value, 600.dp)) {
                AdaptiveHistoryLayout(main = { wide ->
                    var settings by rememberSaveable { mutableStateOf(false) }
                    Column {
                        TextButton({ settings = !settings }, Modifier.testTag("test_settings")) { Text("Settings") }
                        if (settings) Text("Settings form", Modifier.testTag("test_settings_form"))
                        if (!wide) TextButton({ open = true }, Modifier.testTag("test_open")) { Text("Saved") }
                    }
                }, history = { wide, _, _, _ ->
                    HistoryPresentation(wide, open, { open = false }, RecordingRecoveryState(records = listOf(row)), controller, ui,
                        draft = HistoryDraft("Synthetic edited draft", row.id))
                })
            }
        } }
        compose.onNodeWithTag("history_pane").assertExists()
        compose.onNodeWithTag("history_sheet").assertDoesNotExist()
        compose.onNodeWithTag("test_settings").performClick()
        compose.onNodeWithTag("history_expand_adaptive").performClick()
        compose.onNodeWithTag("history_text_adaptive").assertTextContains("Synthetic edited draft")
        compose.runOnIdle { width.value = 400.dp }
        compose.onNodeWithTag("history_sheet").assertExists()
        compose.onNodeWithTag("history_pane").assertDoesNotExist()
        compose.onAllNodesWithTag("history_content").assertCountEquals(1)
        compose.onNodeWithTag("history_text_adaptive").assertTextContains("Synthetic edited draft")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history_text_adaptive").assertTextContains("Synthetic edited draft")
        pressBack()
        compose.onNodeWithTag("history_sheet").assertDoesNotExist()
        compose.onNodeWithTag("test_settings_form").assertExists()
        compose.runOnIdle { width.value = 1000.dp }
        compose.onNodeWithTag("history_pane").assertExists()
        compose.onNodeWithTag("test_settings_form").assertExists()
        compose.onAllNodesWithTag("history_content").assertCountEquals(1)
    }

    @Test fun deleteConfirmationTransfersOnceAndScrollStateIsShared() {
        val width = mutableStateOf(1000.dp)
        lateinit var ui: HistoryUiState
        val records = (0..39).map { row.copy(id = "row-$it") }
        var deletes = 0
        compose.setContent { VoiceDictationTheme {
            ui = rememberHistoryUiState()
            Box(Modifier.requiredSize(width.value, 600.dp)) {
                AdaptiveHistoryLayout(main = {}, history = { wide, _, _, _ ->
                    // Same message implementation with deterministic callbacks, no disk or HTTP.
                    if (wide) Surface(Modifier.fillMaxSize()) {
                        RecordingHistory(RecordingRecoveryState(records = records), io.github.ilyasyoy.voicedictation.playback.PlaybackState(),
                            { true }, {}, {}, { deletes++ }, {}, {}, ui = ui, dismissible = false)
                    } else RecordingHistory(RecordingRecoveryState(records = records), io.github.ilyasyoy.voicedictation.playback.PlaybackState(),
                        { true }, {}, {}, { deletes++ }, {}, {}, ui = ui)
                })
            }
        } }
        compose.onNodeWithTag("history_list").performScrollToIndex(15)
        var index = 0
        compose.runOnIdle { index = ui.list.firstVisibleItemIndex; ui.expanded.add("row-20"); ui.deleteId = "row-20" }
        compose.runOnIdle { width.value = 400.dp }
        compose.onAllNodesWithTag("history_confirm_delete").assertCountEquals(1)
        compose.runOnIdle { assertEquals(index, ui.list.firstVisibleItemIndex); assertTrue("row-20" in ui.expanded) }
        compose.runOnIdle { width.value = 1000.dp }
        compose.onAllNodesWithTag("history_confirm_delete").assertCountEquals(1)
        compose.onNodeWithTag("history_confirm_delete").performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertNull(ui.deleteId) }
    }
}
