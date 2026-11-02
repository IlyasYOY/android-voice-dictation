package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.AndroidAudioRecorder
import io.github.ilyasyoy.voicedictation.data.Transcriber
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import org.junit.*
import org.junit.Assert.*

class SoftCurrentScreenTest {
    @get:org.junit.Rule val existingFeatureSetup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /sdcard/Download/soft-current-evidence", "screencap -p /sdcard/Download/soft-current-evidence/$name.png")) {
            automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }
    @Test fun settingsNavigationKeepsDraftWithoutAnyModeSelector() {
        lateinit var model: DictationViewModel
        MainActivity.testNotice = "DESIGN TEST · synthetic draft · no API call"
        MainActivity.testFactory = { context ->
            DictationViewModel(AndroidAudioRecorder(context), Transcriber { _, _ -> error("UI navigation must never send audio") })
                .also { model=it; it.setKey("synthetic-design-key"); it.edit("A thought kept in this session.") }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("mode_live").assertDoesNotExist()
                compose.onNodeWithTag("mode_record").assertDoesNotExist()
                capture("01-main-session")
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                capture("02-settings")
                compose.onNodeWithTag("api_key").performScrollTo()
                capture("03-settings-key-masked")
                compose.onNodeWithTag("close_settings").performScrollTo().performClick()
                capture("04-main-record-only")
                assertEquals("A thought kept in this session.",model.state.value.text)
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                compose.onNodeWithTag("close_settings").assertExists()
                compose.waitForIdle()
                scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("open_settings").fetchSemanticsNodes().size==1 }
                compose.onNodeWithTag("open_settings").assertExists()
                assertFalse(model.state.value.audioBusy)
                assertEquals("A thought kept in this session.",model.state.value.text)
            }
        } finally { MainActivity.testFactory=null; MainActivity.testNotice=null }
    }
}
