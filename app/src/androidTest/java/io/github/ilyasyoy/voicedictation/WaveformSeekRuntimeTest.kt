package io.github.ilyasyoy.voicedictation

import android.os.ParcelFileDescriptor
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.HistoryViewModel
import io.github.ilyasyoy.voicedictation.ui.HistoryPresentation
import io.github.ilyasyoy.voicedictation.ui.rememberHistoryUiState
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.*
import org.junit.Assert.*

/** Synthetic local PCM; real MediaPlayer, history, touch gestures and accessibility actions. */
class WaveformSeekRuntimeTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = AndroidRecordingStorage.repository(context)
    private var animation = "1"
    @Before fun before() {
        animation = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
        instrumentation.runOnMainSync {
            DictationSessions.prepare(context)
            repository.records().forEach { repository.discard(it.id) }
            repository.saveText(RecordingTextSlot.MAIN, "")
        }
    }
    @After fun after() {
        instrumentation.runOnMainSync {
            AndroidRecordingPlayback.interrupt()
            repository.records().forEach { repository.discard(it.id) }
        }
        compose.mainClock.autoAdvance = true
        shell("settings put global animator_duration_scale $animation")
    }
    private fun seed(milliseconds: Int): String {
        val file = repository.allocate()
        val samples = milliseconds * 16
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { i ->
            val envelope = .15 + .75 * kotlin.math.abs(kotlin.math.sin(i / 8100.0))
            bytes.putShort((kotlin.math.sin(i * 2 * Math.PI * 220 / 16000) * 6000 * envelope).toInt().toShort())
        }
        file.writeBytes(bytes.array()); repository.finalized(file, milliseconds / 1000)
        repository.complete(checkNotNull(repository.beginUpload(file)), "Синтетическая запись для проверки перемотки.")
        return checkNotNull(repository.record(file)).id
    }
    @Test fun waveformTapDragPauseCancelAndSelectionUseTheActualPlayer() {
        lateinit var first: String; lateinit var second: String
        instrumentation.runOnMainSync { first = seed(90_000); second = seed(90_000) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HistoryViewModel
            scenario.onActivity { model = ViewModelProvider(it)[HistoryViewModel::class.java] }
            compose.onNodeWithTag("saved_recordings").performClick()
            val wave = compose.onNodeWithTag("history_seek_$first")
            wave.performScrollTo().performTouchInput { click(Offset(width * .4f, height / 2f)) }
            compose.waitUntil(20_000) { model.playback.state.value.id == first && !model.playback.state.value.loading && !model.playback.state.value.seeking }
            assertFalse(model.playback.state.value.playing)
            assertTrue(kotlin.math.abs(model.playback.state.value.position - 36_000) <= 100)
            capture("seek-paused-tap", first)
            wave.performTouchInput { swipe(Offset(width * .4f, height / 2f), Offset(width * .7f, height / 2f), 400) }
            compose.waitUntil(15_000) { !model.playback.state.value.seeking && model.playback.state.value.position > 60_000 }
            assertFalse(model.playback.state.value.playing)
            val paused = model.playback.state.value.position
            wave.performTouchInput {
                down(Offset(width * .7f, height / 2f)); moveTo(Offset(width * .2f, height / 2f)); cancel()
            }
            compose.waitForIdle()
            assertEquals(paused, model.playback.state.value.position)
            wave.performTouchInput { swipeUp(durationMillis = 300) }
            assertEquals(paused, model.playback.state.value.position)
            compose.onNodeWithTag("history_play_$first").performScrollTo().performClick()
            compose.waitUntil(15_000) { model.playback.state.value.playing }
            // Real playback produces continuous progress. Drive frames explicitly rather than
            // asking Espresso to wait for an animation/progress stream to stop by itself.
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(32)
            wave.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.25f)) }
            compose.waitUntil(15_000) { !model.playback.state.value.seeking && model.playback.state.value.position < 30_000 }
            assertTrue(model.playback.state.value.playing)
            compose.mainClock.advanceTimeBy(32)
            wave.performTouchInput { swipe(Offset(width * .25f, height / 2f), Offset(width * .4f, height / 2f), 300) }
            compose.waitUntil(15_000) { !model.playback.state.value.seeking && model.playback.state.value.position >= 36_000 }
            assertTrue(model.playback.state.value.playing)
            compose.mainClock.advanceTimeBy(250)
            capture("seek-playing", first)
            compose.onNodeWithTag("history_play_$first").performClick()
            assertFalse(model.playback.state.value.playing)
            compose.mainClock.autoAdvance = true
            wave.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
            compose.waitUntil(15_000) { !model.playback.state.value.seeking }
            assertEquals(model.playback.state.value.duration, model.playback.state.value.position)
            compose.onNodeWithTag("history_seek_$second").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.5f)) }
            compose.waitUntil(15_000) { model.playback.state.value.id == second && !model.playback.state.value.seeking && !model.playback.state.value.loading }
            assertFalse(model.playback.state.value.playing)
            assertTrue(kotlin.math.abs(model.playback.state.value.position - 45_000) <= 100)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertNull(model.playback.state.value.id)
        }
    }
    @Test fun playThenSeekEndBeforePreparedDoesNotFail() {
        lateinit var id: String
        instrumentation.runOnMainSync { id = seed(12_000) }
        val owner = AndroidRecordingPlayback.create(context)
        try {
            instrumentation.runOnMainSync {
                AndroidRecordingPlayback.claim(owner)
                owner.toggle(id, repository.audio(id))
                owner.seek(id, repository.audio(id), 1f)
            }
            compose.waitUntil(15_000) { !owner.state.value.loading && !owner.state.value.seeking }
            assertNull(owner.state.value.failedId); assertEquals(id, owner.state.value.id)
            assertFalse(owner.state.value.playing)
            assertTrue(owner.state.value.duration > 0)
            assertEquals(owner.state.value.duration, owner.state.value.position)
            instrumentation.runOnMainSync { owner.toggle(id, repository.audio(id)) }
            compose.waitUntil(15_000) { !owner.state.value.seeking }
            assertTrue(owner.state.value.playing); assertNull(owner.state.value.failedId)
            instrumentation.runOnMainSync { owner.toggle(id, repository.audio(id)) }
            assertFalse(owner.state.value.playing); assertNull(owner.state.value.failedId)
            assertTrue(repository.audio(id).exists())
        } finally { instrumentation.runOnMainSync { AndroidRecordingPlayback.release(owner) } }
    }
    @Test fun tinyRecordingSeeksToBothEndsAndRewindsBeforePlaying() {
        lateinit var id: String
        instrumentation.runOnMainSync { id = seed(180) }
        val owner = AndroidRecordingPlayback.create(context)
        try {
            instrumentation.runOnMainSync { AndroidRecordingPlayback.claim(owner); owner.seek(id, repository.audio(id), 1f) }
            compose.waitUntil(15_000) { !owner.state.value.loading && !owner.state.value.seeking && owner.state.value.id == id }
            assertTrue(owner.state.value.duration in 1..500)
            assertEquals(owner.state.value.duration, owner.state.value.position)
            assertFalse(owner.state.value.playing)
            instrumentation.runOnMainSync { owner.seek(id, repository.audio(id), 0f) }
            compose.waitUntil(15_000) { !owner.state.value.seeking }
            assertEquals(0, owner.state.value.position)
            instrumentation.runOnMainSync { owner.seek(id, repository.audio(id), 1f) }
            compose.waitUntil(15_000) { !owner.state.value.seeking }
            instrumentation.runOnMainSync { owner.toggle(id, repository.audio(id)) }
            compose.waitUntil(15_000) { owner.state.value.id == null }
            assertTrue(repository.audio(id).exists())
        } finally { instrumentation.runOnMainSync { AndroidRecordingPlayback.release(owner) } }
    }
    @Test fun darkNarrowHistoryOffersTalkBackAdjustAndTenSecondActions() {
        lateinit var id: String
        instrumentation.runOnMainSync { id = seed(30_000) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HistoryViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[HistoryViewModel::class.java]
                activity.setContent {
                    val configuration = Configuration(activity.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag("ru"))
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalConfiguration provides configuration,
                        LocalContext provides activity.createConfigurationContext(configuration),
                        LocalDensity provides Density(density.density, 2f)) {
                        VoiceDictationTheme {
                            val records by RecordingRecovery.processOwned.state.collectAsState()
                            Box(Modifier.width(280.dp).fillMaxHeight()) {
                                HistoryPresentation(true, true, {}, records, model, rememberHistoryUiState(), embedded = true)
                            }
                        }
                    }
                }
            }
            val wave = compose.onNodeWithTag("history_seek_$id")
            wave.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.5f)) }
            compose.waitUntil(20_000) { model.playback.state.value.id == id && !model.playback.state.value.loading && !model.playback.state.value.seeking }
            val actions = wave.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(2, actions.size)
            assertTrue(actions[0].label.contains("10"))
            instrumentation.runOnMainSync { assertTrue(actions[0].action()) }
            compose.waitUntil(15_000) { !model.playback.state.value.seeking }
            assertTrue(kotlin.math.abs(model.playback.state.value.position - 5000) <= 100)
            val forward = wave.fetchSemanticsNode().config[SemanticsActions.CustomActions][1]
            instrumentation.runOnMainSync { assertTrue(forward.action()) }
            compose.waitUntil(15_000) { !model.playback.state.value.seeking }
            assertFalse(model.playback.state.value.playing)
            assertTrue(kotlin.math.abs(model.playback.state.value.position - 15_000) <= 100)
            capture("seek-dark-large-font", id)
        }
    }
    private fun capture(name: String, id: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("history_waveform_$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        shell("mkdir -p /sdcard/Download/seek-evidence")
        shell("screencap -p /sdcard/Download/seek-evidence/$name.png")
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
}
