package io.github.ilyasyoy.voicedictation

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.AdaptiveHistoryLayout
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Main presentation only; precise Setup guidance and storage actions remain separate. */
class MainErrorScreenTest {
    @get:org.junit.Rule val existingFeatureSetup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun everyMainProblemUsesOneGenericMessageWithoutDiscardOrInlineSettings() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        var state by mutableStateOf(DictationState(phase=Phase.ERROR,problem=Problem.AUTH,canRetry=true,key="synthetic-key",safetyStopped=true))
        var retries=0;var discards=0;var saved=0
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {VoiceDictationTheme {
                AdaptiveHistoryLayout(drawerCount=2,onDrawerReveal={saved++},main={
                    DictationScreen(state,onKey={},onRecord={},onStop={},onCancel={discards++},onRetry={retries++},
                        onEdit={},onCopy={},onClear={},onForget={},onSave={})
                },history={_,_,_,_->})
            }}}
            for(problem in Problem.entries) {
                compose.runOnIdle {state=state.copy(problem=problem,key=if(problem==Problem.KEY)"" else "synthetic-key")}
                compose.onNodeWithTag("problem").performScrollTo().assertTextEquals(context.getString(R.string.error))
                compose.onNodeWithTag("status").assertDoesNotExist()
                compose.onNodeWithTag("cancel").assertDoesNotExist()
                compose.onNodeWithTag("settings").assertDoesNotExist()
                compose.onNodeWithTag("add_key").assertDoesNotExist()
                compose.onNodeWithTag("safety_stopped").assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.current_settings)).assertDoesNotExist()
                compose.onNodeWithTag("retry").assertExists()
                compose.onNodeWithTag("saved_recordings").assertExists()
            }
            compose.runOnIdle {state=state.copy(problem=Problem.AUTH)}
            compose.onNodeWithTag("retry").performScrollTo().performClick();assertEquals(1,retries)
            compose.onNodeWithTag("saved_recordings").performClick()
            // History reveals after its coroutine moves the sheet, not in the tap callback.
            compose.waitUntil(10_000) {saved==1};assertEquals(1,saved)
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("open_settings").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("api_key").assertExists();assertEquals(0,discards)
        }
    }
}
