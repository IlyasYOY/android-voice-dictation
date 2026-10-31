package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.ilyasyoy.voicedictation.data.AndroidAudioRecorder
import io.github.ilyasyoy.voicedictation.data.Transcriber
import io.github.ilyasyoy.voicedictation.session.Mode
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import org.junit.*
import org.junit.Assert.*

class SoftCurrentScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun settingsNavigationKeepsDraftAndRequiresExplicitModeChoice() {
        lateinit var model: DictationViewModel
        MainActivity.testNotice = "DESIGN TEST · synthetic draft · no API call"
        MainActivity.testFactory = { context ->
            DictationViewModel(AndroidAudioRecorder(context), Transcriber { _, _ -> error("UI navigation must never send audio") })
                .also { model=it; it.setKey("synthetic-design-key"); it.edit("A thought kept in this session.") }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertEquals(Mode.RECORD, model.state.value.mode)
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                compose.onNodeWithTag("mode_live").performScrollTo().performClick()
                compose.onNodeWithTag("close_settings").performScrollTo().performClick()
                assertEquals(Mode.LIVE, model.state.value.mode)
                assertEquals("A thought kept in this session.",model.state.value.text)
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                compose.onNodeWithTag("open_settings").assertExists()
                assertFalse(model.state.value.audioBusy)
                assertEquals("A thought kept in this session.",model.state.value.text)
            }
        } finally { MainActivity.testFactory=null; MainActivity.testNotice=null }
    }
}
