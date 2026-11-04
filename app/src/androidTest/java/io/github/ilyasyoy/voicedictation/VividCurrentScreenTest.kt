package io.github.ilyasyoy.voicedictation

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.audio.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import io.github.ilyasyoy.voicedictation.ui.design.CurrentMarkView
import org.junit.*

/** Actual application UI with explicitly synthetic visual states, no microphone/API calls. */
class VividCurrentScreenTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    @Test fun captureAllMainStatesInBothThemesAndReducedMotion() {
        val source = VoiceLevelSource {
            VoiceLevels(.8f, floatArrayOf(.7f,.6f,.4f,.55f,.8f,.5f,.3f,.5f,.35f), System.nanoTime())
        }
        shell("mkdir -p /sdcard/Download/vivid-current")
        try {
            for (theme in listOf("no", "yes")) {
                // The software emulator may never make Espresso idle while native frames run.
                // Settle UI with scheduling disabled; explicitly step a frozen voice frame below.
                shell("settings put global animator_duration_scale 0")
                shell("cmd uimode night $theme")
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var state by mutableStateOf(DictationState())
                    scenario.onActivity { activity -> activity.setContent {
                        VoiceDictationTheme { DictationScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                            evidenceNotice = "VISUAL TEST · synthetic states · no API call") }
                    } }
                    for ((name, next) in listOf(
                        "idle" to DictationState(),
                        "recording-silence" to DictationState(phase=Phase.RECORDING),
                        "recording-voice" to DictationState(phase=Phase.RECORDING, voiceLevels=source),
                        "processing" to DictationState(phase=Phase.TRANSCRIBING),
                        "error" to DictationState(phase=Phase.ERROR, problem=Problem.NETWORK)
                    )) {
                        compose.runOnIdle { state=next }
                        compose.waitForIdle(); SystemClock.sleep(700)
                        if (name == "recording-voice") {
                            shell("settings put global animator_duration_scale 1")
                            scenario.onActivity { activity ->
                                fun step(view: View) {
                                    if (view is CurrentMarkView) {
                                        view.mark.stop()
                                        repeat(20) { view.mark.advanceFrame(.033f, System.nanoTime(), true) }
                                        view.invalidate()
                                    }
                                    if (view is ViewGroup) for (i in 0 until view.childCount) step(view.getChildAt(i))
                                }
                                step(activity.window.decorView)
                            }
                            SystemClock.sleep(300)
                        }
                        shell("screencap -p /sdcard/Download/vivid-current/main-$name-$theme.png")
                        shell("settings put global animator_duration_scale 0")
                    }
                    shell("settings put global animator_duration_scale 0")
                    compose.runOnIdle { state=DictationState(phase=Phase.RECORDING, voiceLevels=source) }
                    compose.waitForIdle(); SystemClock.sleep(300)
                    shell("screencap -p /sdcard/Download/vivid-current/main-reduced-$theme.png")
                    shell("settings put global animator_duration_scale 1")
                }
            }
        } finally { shell("settings put global animator_duration_scale 1"); shell("cmd uimode night no") }
    }
}
