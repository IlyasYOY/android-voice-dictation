package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.audio.VoiceLevels
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.Phase
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import org.junit.*
import org.junit.Assert.*

/** Screenshots are actual Android windows with actual local capture; emulator mic is silent. */
class AudioReactiveScreenTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        shell("mkdir -p /sdcard/Download/audio-current")
        shell("screencap -p /sdcard/Download/audio-current/$name.png")
    }
    @Test fun realMainCaptureCancelsAndFollowsLightAndDarkTheme() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        lateinit var model: DictationViewModel
        MainActivity.testNotice = "LOCAL EMULATOR · silent microphone · no API call"
        MainActivity.testFactory = { context ->
            DictationViewModel(AndroidAudioRecorder(context), Transcriber { _, _ -> error("No upload authorized in visual test") })
                .also { model = it }
        }
        try {
            for (theme in listOf("no", "yes")) {
                shell("cmd uimode night $theme")
                ActivityScenario.launch(MainActivity::class.java).use {
                    compose.onNodeWithTag("record").assertIsDisplayed()
                    capture("main-idle-$theme")
                    compose.onNodeWithTag("record").performClick()
                    compose.waitUntil(10_000) { model.state.value.phase == Phase.RECORDING && model.state.value.voiceLevels.current().measuredAtNanos > 0L }
                    compose.onNodeWithTag("stop").assertIsDisplayed()
                    capture("main-recording-$theme")
                    compose.onNodeWithTag("cancel").performClick()
                    compose.waitUntil(10_000) { !model.state.value.audioBusy }
                    assertSame(VoiceLevels.Silence,model.state.value.voiceLevels.current())
                    compose.onNodeWithTag("record").assertIsDisplayed()
                }
            }
        } finally {
            MainActivity.testFactory = null; MainActivity.testNotice = null
            shell("cmd uimode night no")
        }
    }
}
