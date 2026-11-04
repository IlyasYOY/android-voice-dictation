package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.*
import io.github.ilyasyoy.voicedictation.data.AudioRecorder
import io.github.ilyasyoy.voicedictation.data.Transcriber
import io.github.ilyasyoy.voicedictation.session.Phase
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import io.github.ilyasyoy.voicedictation.ui.selectHistoryFoldingFeature
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FoldActivityTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()

    @SuppressLint("RestrictedApi") // WindowManager's test decorator, reset in finally.
    @Test fun activityReceivesFlatFoldAndRetainsOneRecordingAcrossRepeatedPosturesAndRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val layouts = MutableStateFlow(WindowLayoutInfo(emptyList()))
        WindowInfoTracker.overrideDecorator(object : WindowInfoTrackerDecorator {
            override fun decorate(tracker: WindowInfoTracker) = object : WindowInfoTracker {
                override fun windowLayoutInfo(activity: Activity) = layouts
            }
        })
        var starts = 0; var cancels = 0; var stops = 0; var uploads = 0
        lateinit var model: DictationViewModel
        MainActivity.testFactory = {
            DictationViewModel(object : AudioRecorder {
                override fun start(onLimit: () -> Unit) { starts++ }
                override fun stop(): java.io.File { stops++; error("No Stop in this fixture") }
                override fun cancel() { cancels++ }
            }, Transcriber { _, _ -> uploads++; error("No HTTP in this fixture") }).also { model = it }
        }
        try {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil { layouts.subscriptionCount.value > 0 }
                var windowWidth = 0
                lateinit var bounds: Rect
                scenario.onActivity {
                    windowWidth = it.window.decorView.width
                    bounds = Rect(windowWidth / 2, 0, windowWidth / 2, it.window.decorView.height)
                }
                compose.runOnIdle { layouts.value = WindowLayoutInfo(listOf(TestFold(bounds))) }
                compose.waitUntil { compose.onNodeWithTag("dictation_pane").fetchSemanticsNode().boundsInRoot.width <= windowWidth / 2f + 1f }
                compose.onNodeWithTag("record").performClick()
                compose.waitUntil { model.state.value.phase == Phase.RECORDING }
                val retained = model
                val pane = compose.onNodeWithTag("dictation_pane").fetchSemanticsNode().boundsInRoot
                val control = compose.onNodeWithTag("stop").fetchSemanticsNode().boundsInRoot
                val baselineCancels = cancels
                repeat(4) {
                    for (half in listOf(true, false)) {
                        compose.runOnIdle { layouts.value = WindowLayoutInfo(listOf(TestFold(bounds, half))) }
                        assertEquals(pane, compose.onNodeWithTag("dictation_pane").fetchSemanticsNode().boundsInRoot)
                        assertEquals(control, compose.onNodeWithTag("stop").fetchSemanticsNode().boundsInRoot)
                        assertEquals(Phase.RECORDING, model.state.value.phase)
                        assertEquals(1, starts); assertEquals(baselineCancels, cancels)
                        assertEquals(0, stops); assertEquals(0, uploads)
                    }
                }
                scenario.recreate()
                compose.onNodeWithTag("stop").assertIsDisplayed()
                assertSame(retained, model); assertEquals(Phase.RECORDING, model.state.value.phase)
                assertEquals(1, starts); assertEquals(baselineCancels, cancels)
                compose.onNodeWithTag("cancel").performScrollTo().performClick()
                compose.waitUntil { !model.state.value.audioBusy }
                assertEquals(0, uploads)
            }
        } finally {
            MainActivity.testFactory = null
            WindowInfoTracker.reset()
        }
    }

    @Test fun featureSelectionKeepsFlatBookGeometryAndPrioritizesRealSeparationAndOcclusion() {
        val flatBook = TestFold(Rect(350, 0, 350, 700))
        val flatHorizontal = TestFold(Rect(0, 350, 700, 350), vertical = false)
        val tabletop = TestFold(Rect(0, 350, 700, 350), half = true, vertical = false)
        val occlusion = TestFold(Rect(0, 330, 700, 370), vertical = false, occluding = true)
        assertSame(flatBook, selectHistoryFoldingFeature(listOf(flatBook)))
        assertNull(selectHistoryFoldingFeature(listOf(flatHorizontal)))
        assertSame(tabletop, selectHistoryFoldingFeature(listOf(flatBook, tabletop)))
        assertSame(occlusion, selectHistoryFoldingFeature(listOf(flatBook, occlusion)))
        assertNull(selectHistoryFoldingFeature(emptyList()))
    }
}
