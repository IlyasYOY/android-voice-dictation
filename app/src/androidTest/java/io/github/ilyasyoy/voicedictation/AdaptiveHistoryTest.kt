package io.github.ilyasyoy.voicedictation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import io.github.ilyasyoy.voicedictation.ui.design.SoftCurrentPalette
import io.github.ilyasyoy.voicedictation.onboarding.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
    private val row = SavedRecording("00000000-0000-0000-0000-000000000001", 1700000000000, 1700000000000,
        SavedRecordingStatus.COMPLETE, 12, "Synthetic retained transcript")

    @Test fun sheetPaneResizePreservesExpansionDraftAndMainSettingsWithoutDuplicateContent() {
        val width = mutableStateOf(700.dp)
        val font = mutableFloatStateOf(1f)
        val restore = StateRestorationTester(compose)
        restore.setContent { CompositionLocalProvider(LocalDensity provides Density(.3f, font.floatValue)) { VoiceDictationTheme {
            val controller: HistoryViewModel = viewModel()
            val ui = rememberHistoryUiState()
            var open by rememberSaveable { mutableStateOf(true) }
            Box(Modifier.requiredSize(width.value, 600.dp)) {
                AdaptiveHistoryLayout(drawerOpen = open, onDrawerExpanded = { open = true }, onDrawerCollapsed = { open = false }, main = { wide ->
                    var settings by rememberSaveable { mutableStateOf(false) }
                    Column {
                        TextButton({ settings = !settings }, Modifier.testTag("test_settings")) { Text("Settings") }
                        if (settings) Text("Settings form", Modifier.testTag("test_settings_form"))
                        if (!wide) TextButton({ open = true }, Modifier.testTag("test_open")) { Text("Saved") }
                    }
                }, history = { wide, _, _, _ ->
                    HistoryPresentation(wide, open, { open = false }, RecordingRecoveryState(records = listOf(row)), controller, ui,
                        draft = HistoryDraft("Synthetic edited draft", row.id), onOpenForDialog = { open = true }, embedded = true)
                })
            }
        } } }
        compose.onNodeWithTag("history_pane").assertExists()
        compose.onNodeWithTag("history_sheet").assertDoesNotExist()
        compose.onNodeWithTag("test_settings").performClick()
        compose.onNodeWithTag("history_expand_${row.id}").performClick()
        compose.onNodeWithTag("history_text_${row.id}").assertTextContains("Synthetic edited draft")
        compose.runOnIdle { width.value = 400.dp }
        compose.onNodeWithTag("history_sheet").assertExists()
        compose.onNodeWithTag("history_pane").assertDoesNotExist()
        compose.onAllNodesWithTag("history_content").assertCountEquals(1)
        compose.onNodeWithTag("history_text_${row.id}").assertTextContains("Synthetic edited draft")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history_text_${row.id}").assertTextContains("Synthetic edited draft")
        pressBack()
        compose.onNodeWithTag("history_sheet").assertIsNotDisplayed()
        compose.onNodeWithTag("test_settings_form").assertExists()
        compose.runOnIdle { width.value = 700.dp }
        compose.onNodeWithTag("history_pane").assertExists()
        compose.onNodeWithTag("test_settings_form").assertExists()
        compose.onAllNodesWithTag("history_content").assertCountEquals(1)
        compose.onNodeWithTag("history_menu_${row.id}").performScrollTo().performClick()
        compose.onNodeWithTag("history_delete_${row.id}").performClick()
        compose.runOnIdle { width.value = 400.dp }
        compose.onNodeWithTag("history_sheet").assertExists()
        compose.onAllNodesWithTag("history_confirm_delete").assertCountEquals(1)
        pressBack() // Cancel confirmation, then dismiss the transferred sheet.
        pressBack()
        compose.runOnIdle { width.value = 700.dp }
        compose.runOnIdle { font.floatValue = 2f }
        compose.onNodeWithTag("history_pane").assertDoesNotExist()
        // The compact presentation was deliberately dismissed earlier; it stays closed.
        compose.onNodeWithTag("history_sheet").assertIsNotDisplayed()
        compose.onNodeWithTag("test_open").performClick()
        compose.onNodeWithTag("history_text_${row.id}").assertTextContains("Synthetic edited draft")
    }

    @Test fun deleteConfirmationTransfersOnceAndScrollStateIsShared() {
        val width = mutableStateOf(700.dp)
        lateinit var ui: HistoryUiState
        val records = (0..39).map { row.copy(id = "row-$it") }
        var deletes = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.3f, 1f)) { VoiceDictationTheme {
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
        } } }
        compose.onNodeWithTag("history_list").performScrollToIndex(15)
        var index = 0
        compose.runOnIdle { index = ui.list.firstVisibleItemIndex; ui.expanded.add("row-20"); ui.deleteId = "row-20" }
        compose.runOnIdle { width.value = 400.dp }
        compose.onAllNodesWithTag("history_confirm_delete").assertCountEquals(1)
        compose.runOnIdle { assertEquals(index, ui.list.firstVisibleItemIndex); assertTrue("row-20" in ui.expanded) }
        compose.runOnIdle { width.value = 700.dp }
        compose.onAllNodesWithTag("history_confirm_delete").assertCountEquals(1)
        compose.onNodeWithTag("history_confirm_delete").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, deletes); assertNull(ui.deleteId) }
    }
    @Test fun deleteAndLimitDialogsStayInReportedVerticalAndHorizontalSafeRegions() {
        val vertical = mutableStateOf(true)
        val limit = mutableStateOf(false)
        var boundary = 0f
        compose.setContent { VoiceDictationTheme {
            val size = LocalWindowInfo.current.containerSize
            val density = LocalDensity.current.density
            val w = size.width / density; val h = size.height / density
            val safe = if (vertical.value) HistoryRegion(w / 2 + 12f, 0f, w / 2 - 12f, h)
                else HistoryRegion(0f, h / 2 + 12f, w, h / 2 - 12f)
            boundary = (if (vertical.value) safe.left else safe.top) * density
            CompositionLocalProvider(LocalHistoryDialogRegion provides safe) {
                if (limit.value) HistoryLimitDialog(HistoryCapacity(), { true }, {})
                else HistorySafeDialog({}, content = { Text("Synthetic delete confirmation") },
                    buttons = { TextButton({}) { Text("Delete") } })
            }
        } }
        for (v in listOf(true, false)) for (l in listOf(false, true)) {
            compose.runOnIdle { vertical.value = v; limit.value = l }
            val bounds = compose.onNodeWithTag("history_safe_dialog").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { if (v) assertTrue(bounds.left >= boundary - 1f) else assertTrue(bounds.top >= boundary - 1f) }
        }
    }

    @Test fun cohesiveOnboardingAndDarkRootSurviveResizeAndWorkspaceTransition() {
        val width = mutableStateOf(700.dp)
        val onboarding = mutableStateOf(true)
        val dark = mutableStateOf(true)
        val candidate = mutableStateOf(SetupKeyDraft())
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(.3f, 1f)) {
                VoiceDictationTheme {
                    val controller: HistoryViewModel = viewModel()
                    val ui = rememberHistoryUiState()
                    Box(Modifier.requiredSize(width.value, 800.dp)) {
                        AdaptiveHistoryLayout(historyEnabled = !onboarding.value, main = { wide ->
                            if (onboarding.value) SetupScreen(SetupUi(step = SetupStep.KEY),
                                io.github.ilyasyoy.voicedictation.session.DictationState(), candidate.value,
                                SetupGrants(true, false, false, false), false, false, false, false, 0,
                                onKey = { candidate.value = SetupKeyDraft(it, active = true) }, onPaste = {}, onBrowser = {},
                                onPrimary = {}, onEnableFloating = {}, onAppSettings = {},
                                onDefer = { onboarding.value = false }, onHistory = {})
                            else DictationScreen(io.github.ilyasyoy.voicedictation.session.DictationState(),
                                {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                        }, history = { wide, _, _, _ -> HistoryPresentation(wide, false, {},
                            RecordingRecoveryState(), controller, ui, embedded = true) })
                    }
                }
            }
        }
        fun assertBackground() {
            val image = compose.onNodeWithTag("adaptive_root").captureToImage().toPixelMap()
            val expected = Color(if (dark.value) SoftCurrentPalette.Night else SoftCurrentPalette.Paper).toArgb()
            assertEquals(expected, image[0, 0].toArgb())
            assertEquals(expected, image[image.width - 1, image.height - 1].toArgb())
        }
        compose.onNodeWithTag("onboarding").assertExists()
        compose.onNodeWithTag("history_pane").assertDoesNotExist()
        compose.onNodeWithTag("history_sheet").assertDoesNotExist()
        compose.onNodeWithTag("setup_key").performTextReplacement("synthetic-staged-text")
        for (w in listOf(400.dp, 700.dp)) {
            compose.runOnIdle { width.value = w }
            compose.onNodeWithTag("setup_key").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.InputText, AnnotatedString("synthetic-staged-text")))
            compose.onNodeWithTag("history_content").assertDoesNotExist()
            assertBackground()
        }
        compose.onNodeWithTag("setup_defer").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
        compose.onNodeWithTag("history_pane").assertExists()
        assertBackground()
        compose.runOnIdle { dark.value = false }
        assertBackground()
        compose.runOnIdle { onboarding.value = true }
        compose.onNodeWithTag("history_content").assertDoesNotExist()
        assertBackground()
    }

}
