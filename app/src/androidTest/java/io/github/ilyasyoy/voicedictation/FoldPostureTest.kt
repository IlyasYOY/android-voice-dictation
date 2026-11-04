package io.github.ilyasyoy.voicedictation

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import io.github.ilyasyoy.voicedictation.data.SavedRecording
import io.github.ilyasyoy.voicedictation.data.SavedRecordingStatus
import io.github.ilyasyoy.voicedictation.session.DictationState
import io.github.ilyasyoy.voicedictation.session.RecordingRecoveryState
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic WindowManager events, not evidence from a physical Fold hinge. */
class FoldPostureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun verticalPostureChangeAtFixedWindowKeepsPaneAndControlBounds() {
        val width = mutableStateOf(700.dp)
        val font = mutableFloatStateOf(1f)
        val half = mutableStateOf(false)
        val origin = mutableStateOf(Offset.Zero)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.3f, font.floatValue)) {
                VoiceDictationTheme {
                    Box(Modifier.requiredSize(width.value, 600.dp).onGloballyPositioned { origin.value = it.positionInWindow() }) {
                        val x = origin.value.x.toInt() + (width.value.value * .3f / 2).toInt()
                        val y = origin.value.y.toInt()
                        AdaptiveHistoryLayout(hinge = TestFold(Rect(x, y, x, y + 180), half.value),
                            main = { DictationScreen(DictationState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) },
                            history = { _, _, _, _ -> Text("History", Modifier.testTag("fold_history")) })
                    }
                }
            }
        }
        for (w in listOf(616.dp, 640.dp, 673.dp, 700.dp)) for (f in listOf(1f, 1.2f, 2f)) {
            compose.runOnIdle { width.value = w; font.floatValue = f; half.value = false }
            val pane = compose.onNodeWithTag("dictation_pane").fetchSemanticsNode().boundsInRoot
            val control = compose.onNodeWithTag("record").fetchSemanticsNode().boundsInRoot
            val historyCount = compose.onAllNodesWithTag("fold_history").fetchSemanticsNodes().size
            repeat(3) {
                compose.runOnIdle { half.value = true }
                assertEquals("pane at $w font $f", pane, compose.onNodeWithTag("dictation_pane").fetchSemanticsNode().boundsInRoot)
                assertEquals("control at $w font $f", control, compose.onNodeWithTag("record").fetchSemanticsNode().boundsInRoot)
                compose.onAllNodesWithTag("fold_history").assertCountEquals(historyCount)
                compose.runOnIdle { half.value = false }
                assertEquals(pane, compose.onNodeWithTag("dictation_pane").fetchSemanticsNode().boundsInRoot)
            }
        }
    }

    @Test fun windowAndPostureChangesPreserveSettingsExpandedHistoryAndEditedDraft() {
        val width = mutableStateOf(700.dp)
        val half = mutableStateOf(false)
        val origin = mutableStateOf(Offset.Zero)
        val row = SavedRecording("00000000-0000-0000-0000-000000000002", 1700000000000, 1700000000000, SavedRecordingStatus.COMPLETE, 12, "Synthetic transcript")
        lateinit var ui: HistoryUiState
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.3f, 1f)) { VoiceDictationTheme {
                val controller: HistoryViewModel = viewModel()
                ui = rememberHistoryUiState()
                var open by rememberSaveable { mutableStateOf(true) }
                Box(Modifier.requiredSize(width.value, 800.dp).onGloballyPositioned { origin.value = it.positionInWindow() }) {
                    val x = origin.value.x.toInt() + (width.value.value * .3f / 2).toInt()
                    val y = origin.value.y.toInt()
                    AdaptiveHistoryLayout(TestFold(Rect(x, y, x, y + 240), half.value), drawerOpen = open,
                        onDrawerExpanded = { open = true }, onDrawerCollapsed = { open = false },
                        main = { DictationScreen(DictationState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) },
                        history = { wide, _, _, _ ->
                            HistoryPresentation(wide, open, { open = false }, RecordingRecoveryState(records = listOf(row)),
                                controller, ui, draft = HistoryDraft("Synthetic edited draft", row.id),
                                onOpenForDialog = { open = true }, embedded = true)
                        })
                }
            } }
        }
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("history_expand_${row.id}").performClick()
        for (w in listOf(673.dp, 616.dp, 700.dp, 400.dp, 700.dp)) {
            compose.runOnIdle { width.value = w }
            for (posture in listOf(true, false)) {
                compose.runOnIdle { half.value = posture }
                compose.onNodeWithTag("close_settings").assertExists()
                compose.onNodeWithTag("history_text_${row.id}").assertTextContains("Synthetic edited draft")
                compose.onAllNodesWithTag("history_content").assertCountEquals(1)
                compose.runOnIdle { assertTrue(row.id in ui.expanded) }
            }
        }
        compose.onNodeWithTag("history_pane").assertExists()
    }
}

internal class TestFold(
    private val rectangle: Rect,
    private val half: Boolean = false,
    private val vertical: Boolean = true,
    private val occluding: Boolean = false,
) : FoldingFeature {
    override val bounds get() = Rect(rectangle)
    override val state get() = if (half) FoldingFeature.State.HALF_OPENED else FoldingFeature.State.FLAT
    override val orientation get() = if (vertical) FoldingFeature.Orientation.VERTICAL else FoldingFeature.Orientation.HORIZONTAL
    override val occlusionType get() = if (occluding) FoldingFeature.OcclusionType.FULL else FoldingFeature.OcclusionType.NONE
    override val isSeparating get() = half || occluding
}
