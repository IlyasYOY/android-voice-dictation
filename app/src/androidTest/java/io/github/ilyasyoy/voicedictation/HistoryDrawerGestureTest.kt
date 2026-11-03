package io.github.ilyasyoy.voicedictation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.PlaybackState
import io.github.ilyasyoy.voicedictation.session.RecordingRecoveryState
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real pointer injection into the production scaffold, not a Boolean swipe callback or static render. */
@OptIn(ExperimentalMaterial3Api::class)
class HistoryDrawerGestureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dragTracksFingerBeforeReleaseThenSettlesBothWaysAndTapWorks() {
        lateinit var sheet: SheetState
        var open by mutableStateOf(false)
        var mainClicks = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { VoiceDictationTheme {
            sheet = rememberStandardBottomSheetState()
            Box(Modifier.requiredSize(360.dp, 600.dp)) {
                HistoryDrawer(sheet, open, true, 2, {}, { open = true }, { open = false },
                    main = { padding -> Button({ mainClicks++ }, Modifier.padding(padding).testTag("main_action")) { Text("Main") } },
                    content = { Text("Panel content", Modifier.testTag("drawer_test_content")) })
            }
        } } }
        compose.onNodeWithTag("main_action").performClick()
        compose.runOnIdle { assertEquals(1, mainClicks); assertEquals(524f, sheet.requireOffset(), 1f) }
        compose.onAllNodesWithTag("history_swipe", useUnmergedTree = true).assertCountEquals(1)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("history_swipe", useUnmergedTree = true).performTouchInput { down(center); moveBy(Offset(0f, -150f), 200) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("The same surface must move while the finger is still down", sheet.requireOffset() in 250f..450f)
            assertFalse(open)
        }
        compose.onNodeWithTag("history_swipe", useUnmergedTree = true).performTouchInput { moveBy(Offset(0f, -250f), 200); up() }
        compose.mainClock.autoAdvance = true; compose.waitForIdle()
        compose.runOnIdle { assertEquals(SheetValue.Expanded, sheet.currentValue); assertTrue(open) }
        compose.onAllNodesWithTag("history_swipe", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("drawer_test_content").assertCountEquals(1)
        compose.onNodeWithTag("history_swipe", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, 400f), 600)
        }
        compose.runOnIdle { assertEquals(SheetValue.PartiallyExpanded, sheet.currentValue); assertFalse(open) }
        compose.onNodeWithTag("saved_recordings").performClick()
        compose.runOnIdle { assertEquals(SheetValue.Expanded, sheet.currentValue) }
        compose.onNodeWithTag("saved_recordings").performClick()
        compose.runOnIdle { assertEquals(SheetValue.PartiallyExpanded, sheet.currentValue) }
    }

    @Test fun listConsumesDownwardScrollUntilTopThenDrawerCollapses() {
        lateinit var sheet: SheetState
        lateinit var ui: HistoryUiState
        var open by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { VoiceDictationTheme {
            sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded)
            ui = rememberHistoryUiState()
            Box(Modifier.requiredSize(360.dp, 600.dp)) {
                HistoryDrawer(sheet, open, true, 40, {}, { open = true }, { open = false }, main = {}, content = {
                    LazyColumn(Modifier.fillMaxSize().testTag("nested_list"), state = ui.list) {
                        items((0..39).toList()) { Text("Item $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                })
            }
        } } }
        compose.onNodeWithTag("nested_list").performScrollToIndex(20)
        compose.onNodeWithTag("nested_list").performTouchInput { swipe(center, center + Offset(0f, 140f), 2000) }
        compose.runOnIdle {
            assertEquals(SheetValue.Expanded, sheet.currentValue)
            assertTrue(ui.list.firstVisibleItemIndex in 15..19)
        }
        compose.onNodeWithTag("nested_list").performScrollToIndex(0)
        compose.onNodeWithTag("nested_list").performTouchInput { swipe(center, center + Offset(0f, 220f), 400) }
        compose.runOnIdle { assertEquals(SheetValue.PartiallyExpanded, sheet.currentValue) }
    }

    @Test fun wordsInputAndMainStateSurviveKeyboardDragAndCompactWideTransfers() {
        val width = mutableStateOf(400.dp)
        var open by mutableStateOf(false)
        lateinit var ui: HistoryUiState
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { VoiceDictationTheme {
            ui = rememberHistoryUiState()
            Box(Modifier.requiredSize(width.value, 600.dp)) {
                AdaptiveHistoryLayout(drawerOpen = open, onDrawerExpanded = { open = true },
                    onDrawerCollapsed = { open = false; ui.closeTransient() }, main = {
                        var counter by rememberSaveable { mutableIntStateOf(0) }
                        Button({ counter++ }, Modifier.testTag("main_counter")) { Text("$counter") }
                    }, history = { _, _, _, _ ->
                        HistoryPanel(ui, {}, Modifier.fillMaxSize(), words = { modifier ->
                            VocabularyContent(VocabularyState(listOf("Neovim"), true), false, null, ui, { _, _, _ -> }, {}, {}, modifier)
                        }, history = { Text("History") })
                    })
            }
        } } }
        compose.onNodeWithTag("main_counter").performClick()
        compose.onNodeWithTag("history_swipe", useUnmergedTree = true).performTouchInput { swipe(center, center - Offset(0f, 400f), 400) }
        compose.onNodeWithTag("words_tab").performClick()
        compose.onNodeWithTag("words_add_input").performTextInput("unfinished term")
        if (!compose.onNodeWithTag("words_add").isDisplayed()) compose.onNodeWithTag("words_add").performScrollTo()
        compose.onNodeWithTag("words_add").assertIsDisplayed()
        compose.onNodeWithTag("history_swipe", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(0f, 300f), 400) }
        compose.onNodeWithTag("saved_recordings").performClick()
        compose.onNodeWithTag("words_add_input").assertTextContains("unfinished term")
        compose.runOnIdle { width.value = 700.dp }
        compose.onNodeWithTag("history_drawer").assertDoesNotExist()
        compose.onAllNodesWithTag("history_tabs").assertCountEquals(1)
        compose.onNodeWithTag("main_counter").assertTextContains("1")
        compose.onNodeWithTag("words_add_input").assertTextContains("unfinished term")
        compose.runOnIdle { width.value = 400.dp }
        compose.onNodeWithTag("words_add_input").assertTextContains("unfinished term")
        compose.runOnIdle { assertTrue(ui.wordsSelected); assertTrue(open) }
    }

    @Test fun confirmationReceivesBackBeforeDrawerAndMain() {
        var open by mutableStateOf(true)
        var mainBacks = 0
        val row = SavedRecording("drawer-row", 1, 1, SavedRecordingStatus.COMPLETE, transcript = "Synthetic")
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { VoiceDictationTheme {
            val ui = rememberHistoryUiState()
            val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded)
            BackHandler { mainBacks++ }
            Box(Modifier.requiredSize(360.dp, 600.dp)) {
                HistoryDrawer(sheet, open, true, 1, {}, { open = true }, { open = false }, main = {}, content = {
                    RecordingHistory(RecordingRecoveryState(records = listOf(row)), PlaybackState(), { false }, {}, {}, {}, {}, {}, ui = ui)
                })
            }
        } } }
        compose.onNodeWithTag("history_menu_drawer-row").performClick()
        compose.onNodeWithTag("history_delete_drawer-row").performClick()
        pressBack()
        compose.onNodeWithTag("history_confirm_delete").assertDoesNotExist()
        compose.runOnIdle { assertTrue(open); assertEquals(0, mainBacks) }
        pressBack()
        compose.runOnIdle { assertFalse(open); assertEquals(0, mainBacks) }
        pressBack()
        compose.runOnIdle { assertEquals(1, mainBacks) }
    }
}
