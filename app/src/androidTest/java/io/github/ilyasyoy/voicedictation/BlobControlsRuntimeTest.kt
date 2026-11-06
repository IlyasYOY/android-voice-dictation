package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

/** Real Compose pointer/accessibility gestures. Recorder/response are synthetic; no HTTP. */
class BlobControlsRuntimeTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var animations = "1"
    private var night = "no"
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    @Before fun before() {
        animations=shell("settings get global animator_duration_scale")
        night=shell("cmd uimode night").substringAfter(":").trim()
        shell("settings put global animator_duration_scale 0")
    }
    @After fun after() {
        MainActivity.testFactory=null;MainActivity.testNotice=null
        shell("settings put global animator_duration_scale $animations")
        shell("cmd uimode night $night")
    }
    private fun hold(tag:String) = compose.onNodeWithTag(tag).performScrollTo().performTouchInput {longClick()}
    private fun capture(name:String) {
        compose.waitForIdle()
        shell("mkdir -p /sdcard/Download/shared-controls")
        shell("screencap -p /sdcard/Download/shared-controls/$name.png")
    }
    @Test fun realSessionTapsStopOnceAndHoldRevealsCancelWithoutUploadingOrLosingDraft() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val response=CompletableDeferred<String>()
        val audio=File(context.cacheDir,"synthetic-controls.m4a")
        var starts=0;var stops=0;var uploads=0
        lateinit var model:DictationViewModel
        MainActivity.testNotice="GESTURE TEST · synthetic audio · no API call"
        MainActivity.testFactory={
            DictationViewModel(object:AudioRecorder {
                override fun start(onLimit:()->Unit) {starts++}
                override fun stop():File {stops++;return audio.apply {writeText("synthetic")}}
                override fun cancel()=Unit
            },Transcriber {_,_->uploads++;response.await()}).also {model=it;it.setKey("synthetic-controls-key");it.edit("Keep current words")}
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("record").performTouchInput {click()}
                compose.waitUntil(15_000) {model.state.value.phase==Phase.RECORDING}
                compose.onNodeWithTag("cancel").assertDoesNotExist()
                compose.onNodeWithTag("controls_hint").assertTextEquals(context.getString(R.string.current_bubble_hint))
                hold("stop")
                compose.onNodeWithTag("cancel").assertIsDisplayed()
                assertEquals(1,starts);assertEquals(0,stops);assertEquals(0,uploads)
                hold("stop");compose.onNodeWithTag("cancel").assertDoesNotExist()
                hold("stop")
                compose.onNodeWithTag("stop").performTouchInput {click()}
                compose.waitUntil(15_000) {model.state.value.phase==Phase.TRANSCRIBING && uploads==1}
                compose.onNodeWithTag("cancel").assertDoesNotExist()
                compose.onNodeWithTag("processing").performTouchInput {click()}
                assertEquals(1,starts);assertEquals(1,stops);assertEquals(1,uploads)
                hold("processing")
                compose.onNodeWithTag("cancel").performScrollTo().performClick()
                compose.waitUntil(15_000) {!model.state.value.audioBusy}
                assertEquals("Keep current words",model.state.value.text)
                assertFalse(audio.exists())
                response.complete("Late response must not replace the draft")
                compose.waitForIdle();assertEquals("Keep current words",model.state.value.text)
                compose.onNodeWithTag("record").performClick()
                compose.waitUntil(15_000) {model.state.value.phase==Phase.RECORDING}
                assertEquals(2,starts);compose.onNodeWithTag("cancel").assertDoesNotExist()
                hold("stop");compose.onNodeWithTag("cancel").performScrollTo().performClick()
            }
        } finally {audio.delete()}
    }
    @Test fun accessibilityOffersTheSameHoldControlsAndExplicitCancelDuringBothBusyPhases() {
        var state by mutableStateOf(DictationState(phase=Phase.RECORDING))
        var cancels=0;var taps=0
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {it.setContent {VoiceDictationTheme {
                DictationScreen(state,{}, {taps++}, {taps++}, {cancels++;state=DictationState()}, {}, {}, {}, {}, {}, {})
            }}}
            for(phase in listOf(Phase.RECORDING,Phase.TRANSCRIBING)) {
                compose.runOnIdle {state=DictationState(phase=phase,keyBusy=phase==Phase.TRANSCRIBING)}
                val tag=if(phase==Phase.RECORDING)"stop" else "processing"
                val node=compose.onNodeWithTag(tag)
                assertEquals(context.getString(R.string.current_controls),node.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
                node.performSemanticsAction(SemanticsActions.OnLongClick) {it()}
                compose.onNodeWithTag("cancel").assertContentDescriptionEquals(context.getString(R.string.cancel));assertEquals(0,taps)
                val action=node.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
                assertEquals(context.getString(R.string.floating_cancel),action.label)
                compose.runOnIdle {assertTrue(action.action())}
                compose.onNodeWithTag("cancel").assertDoesNotExist()
                compose.onNodeWithTag("record").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            }
            assertEquals(2,cancels);assertEquals(0,taps)
        }
    }
    @Test fun completionFailureAndNewSessionClearMenusAndCapacityStillBlocksStarting() {
        var state by mutableStateOf(DictationState(phase=Phase.RECORDING))
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {it.setContent {VoiceDictationTheme {
                DictationScreen(state,{}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }}}
            hold("stop");compose.onNodeWithTag("cancel").assertExists()
            compose.runOnIdle {state=state.copy(phase=Phase.TRANSCRIBING)}
            compose.onNodeWithTag("cancel").assertExists()
            for(phase in listOf(Phase.RESULT,Phase.ERROR,Phase.IDLE)) {
                compose.runOnIdle {state=DictationState(phase=phase,problem=if(phase==Phase.ERROR)Problem.NETWORK else null)}
                compose.onNodeWithTag("cancel").assertDoesNotExist()
                compose.onNodeWithTag("controls_hint").assertDoesNotExist()
                compose.runOnIdle {state=DictationState(phase=Phase.RECORDING)}
                compose.onNodeWithTag("cancel").assertDoesNotExist()
                hold("stop")
            }
            compose.runOnIdle {state=DictationState(capacityBlocked=true)}
            compose.onNodeWithTag("record").assertIsNotEnabled()
            compose.onNodeWithTag("record").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            compose.onNodeWithTag("cancel").assertDoesNotExist()
        }
    }
    @Test fun controlsStayReachableAtLargeFontAndCompactSizeOnBothThemes() {
        for(theme in listOf("no","yes")) {
            shell("cmd uimode night $theme")
            var compact by mutableStateOf(false)
            var state by mutableStateOf(DictationState(phase=Phase.RECORDING))
            var cancels=0
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {it.setContent {
                    val density=LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density,if(compact)2f else 1f)) {
                        VoiceDictationTheme {Box(Modifier.fillMaxSize()) {
                            Box(if(compact)Modifier.size(240.dp,360.dp) else Modifier.fillMaxSize()) {
                                DictationScreen(state,{}, {}, {}, {cancels++}, {}, {}, {}, {}, {}, {},
                                    evidenceNotice="GESTURE TEST · synthetic state · no API call")
                            }
                        }}
                    }
                }}
                compose.onNodeWithTag("cancel").assertDoesNotExist();capture("recording-hint-$theme")
                hold("stop");capture("recording-controls-$theme")
                compose.runOnIdle {state=state.copy(phase=Phase.TRANSCRIBING)}
                compose.onNodeWithTag("cancel").assertExists();capture("processing-controls-$theme")
                compose.runOnIdle {compact=true}
                val cancel=compose.onNodeWithTag("cancel").performScrollTo().assertIsDisplayed()
                val bounds=cancel.getUnclippedBoundsInRoot()
                assertTrue(bounds.right-bounds.left>=48.dp && bounds.bottom-bounds.top>=48.dp)
                assertTrue(bounds.left>=0.dp && bounds.top>=0.dp && bounds.right<=240.dp && bounds.bottom<=360.dp)
                capture("compact-large-font-$theme")
                cancel.performClick();assertEquals(1,cancels)
                compose.onNodeWithTag("cancel").assertDoesNotExist()
            }
        }
    }
}
