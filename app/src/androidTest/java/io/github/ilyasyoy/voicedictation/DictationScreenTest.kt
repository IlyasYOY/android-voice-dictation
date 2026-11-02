package io.github.ilyasyoy.voicedictation

import android.Manifest
import androidx.compose.ui.semantics.SemanticsActions
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.ilyasyoy.voicedictation.data.AndroidAudioRecorder
import io.github.ilyasyoy.voicedictation.data.OpenAiTranscriber
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import io.github.ilyasyoy.voicedictation.session.Phase
import io.github.ilyasyoy.voicedictation.session.RecordingRecovery
import io.github.ilyasyoy.voicedictation.session.StoredRecordingRetention
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import io.github.ilyasyoy.voicedictation.data.AndroidRecordingStorage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Activity, MediaRecorder and HTTP client. Only the API server response is synthetic. */
class DictationScreenTest {
    @get:org.junit.Rule val existingFeatureSetup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var server: MockWebServer
    private lateinit var model: DictationViewModel
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    } }
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val endpoint = server.url("/v1/audio/transcriptions")
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        MainActivity.testNotice = "TEST SERVER · synthetic text · no OpenAI call"
        MainActivity.testFactory = {
            DictationSessions.prepare(it)
            DictationViewModel(AndroidAudioRecorder(it), OpenAiTranscriber(OkHttpClient.Builder()
                .retryOnConnectionFailure(false).build(), endpoint),recordings=StoredRecordingRetention(AndroidRecordingStorage.repository(it))).also { m -> model = m }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        instrumentation.runOnMainSync {RecordingRecovery.processOwned.discard()}
        MainActivity.testFactory = null; MainActivity.testNotice = null
        server.shutdown()
    }
    private fun key() { scenario.onActivity { model.setKey("synthetic-test-key") } }
    private fun waitPhase(phase: Phase) { compose.waitUntil(30_000) { model.state.value.phase == phase } }
    private fun click(tag: String) {
        if (tag == "history_close") {
            compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }; return
        }
        val configuration = tag in setOf("mode_record", "mode_live", "forget")
        if (configuration) compose.onNodeWithTag("open_settings").performScrollTo().performClick()
        if (tag in setOf("saved_recordings", "history_close")) compose.onNodeWithTag(tag).performClick() else compose.onNodeWithTag(tag).performScrollTo().performClick()
        if (configuration) compose.onNodeWithTag("close_settings").performScrollTo().performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(350)
        val out = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        shell("mkdir -p /sdcard/Download/dictation-evidence")
        shell("cp ${File(out, "$name.png").absolutePath} /sdcard/Download/dictation-evidence/$name.png")
    }
    private fun assertNoPendingAudio() {
        assertTrue(AndroidRecordingStorage.repository(context).pending().isEmpty())
    }
    private fun assertSavedAudio(text:String) {
        val store=AndroidRecordingStorage.repository(context)
        val record=store.records().first {it.transcript==text}
        assertTrue(store.audio(record.id).length()>1000)
    }
    @Test fun recordUploadEditCopyAndClear() {
        screenshot("01-idle")
        compose.onNodeWithTag("record").assertIsEnabled()
        key()
        server.enqueue(MockResponse().setBody("{\"text\":\"A thought captured. Test server response.\"}").setBodyDelay(5, TimeUnit.SECONDS))
        click("record"); waitPhase(Phase.RECORDING); screenshot("02-recording")
        SystemClock.sleep(600); click("stop"); waitPhase(Phase.TRANSCRIBING); screenshot("03-transcribing")
        waitPhase(Phase.RESULT); screenshot("04-result");assertSavedAudio("A thought captured. Test server response.")
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.bodySize > 1000); assertEquals("Bearer synthetic-test-key", request.getHeader("Authorization"))
        val id = AndroidRecordingStorage.repository(context).records().first { it.transcript == "A thought captured. Test server response." }.id
        click("saved_recordings")
        compose.waitUntil(20000) { compose.onAllNodesWithTag("history_waveform_$id").fetchSemanticsNodes().isNotEmpty() }
        val peaks = kotlinx.coroutines.runBlocking {
            io.github.ilyasyoy.voicedictation.playback.AndroidAudioWaveform.read(AndroidRecordingStorage.repository(context).audio(id))
        }
        assertEquals("Actual AAC has a decodable local thumbnail", 40, checkNotNull(peaks).size)
        compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
        compose.onNodeWithTag("transcript").performScrollTo().performTextReplacement("Edited thought")
        click("history_copy_$id")
        scenario.onActivity { assertEquals("Edited thought", it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
        assertNoPendingAudio(); click("history_menu_$id"); compose.onNodeWithTag("clear").performClick(); assertEquals("", model.state.value.text)
        click("history_close")
        click("forget"); assertEquals("", model.state.value.key)
    }
    @Test fun serverErrorCanRetryThenRetainsAudioAndText() {
        key(); server.enqueue(MockResponse().setResponseCode(500).setBody("private error"))
        server.enqueue(MockResponse().setBody("{\"text\":\"Recovered via explicit retry.\"}"))
        click("record"); waitPhase(Phase.RECORDING); SystemClock.sleep(1600); click("stop")
        waitPhase(Phase.ERROR)
        compose.onNodeWithTag("problem").assertTextEquals(context.getString(R.string.error))
        compose.onNodeWithTag("status").assertDoesNotExist()
        compose.onNodeWithTag("cancel").assertDoesNotExist()
        compose.onNodeWithTag("open_settings").assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.current_settings)).assertDoesNotExist()
        compose.onNodeWithTag("saved_recordings").assertIsDisplayed()
        screenshot("05-error-retry"); assertTrue(model.state.value.canRetry)
        click("retry"); waitPhase(Phase.RESULT); assertEquals(2, server.requestCount);assertSavedAudio("Recovered via explicit retry.");assertNoPendingAudio()
    }
    @Test fun appGeneratedShortAndNormalAacLoadWaveformsInHistory() {
        key()
        for ((index, length) in listOf(600L, 10000L).withIndex()) {
            val text = "Synthetic AAC fixture $length"
            server.enqueue(MockResponse().setBody("{\"text\":\"$text\"}"))
            click("record");waitPhase(Phase.RECORDING);SystemClock.sleep(length);click("stop");waitPhase(Phase.RESULT)
            val store = AndroidRecordingStorage.repository(context)
            val row = store.records().first { it.transcript == text }
            val file = store.audio(row.id)
            val before = file.readBytes()
            // Load through the real sheet first: no metadata call or cache prewarming.
            click("saved_recordings")
            compose.waitUntil(20000) { compose.onAllNodesWithTag("history_waveform_${row.id}").fetchSemanticsNodes().isNotEmpty() }
            val peaks = kotlinx.coroutines.runBlocking { io.github.ilyasyoy.voicedictation.playback.AndroidAudioWaveform.read(file) }
            assertEquals(40, checkNotNull(peaks).size)
            assertArrayEquals(before, file.readBytes())
            compose.onNodeWithTag("history_play_${row.id}").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("Pause", substring=true)).fetchSemanticsNodes().isNotEmpty() }
            screenshot("aac-$length-real-recorder-waveform")
            compose.onNodeWithTag("history_play_${row.id}").performClick()
            compose.onNodeWithTag("history_expand_${row.id}").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").assertTextEquals(text)
            assertEquals(index + 1, server.requestCount)
            click("history_close")
        }
    }
    @Test fun failedCurrentRecordingCanBePlayedAndRetriedInMainHistoryWithoutBackgrounding() {
        key();server.enqueue(MockResponse().setResponseCode(500))
        click("record");waitPhase(Phase.RECORDING);SystemClock.sleep(1600);click("stop");waitPhase(Phase.ERROR)
        val store=AndroidRecordingStorage.repository(context)
        val row=store.records().first {it.status==io.github.ilyasyoy.voicedictation.data.SavedRecordingStatus.FAILED}
        assertTrue(row.id in store.ownedIds())
        click("saved_recordings")
        assertFalse(model.state.value.canRetry)
        assertFalse(row.id in store.ownedIds())
        compose.onNodeWithTag("history_retry_${row.id}").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("history_play_${row.id}").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodes(hasContentDescription("Pause", substring=true)).fetchSemanticsNodes().isNotEmpty()}
        screenshot("07-current-failure-history-real-m4a-playing")
        compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }
        assertTrue(store.audio(row.id).length()>1000)
        assertEquals(1,server.requestCount)
    }
    @Test fun cancelAndBackgroundStopRealMicrophone() {
        key(); click("record"); waitPhase(Phase.RECORDING); click("cancel"); waitPhase(Phase.IDLE); assertNoPendingAudio()
        click("record"); waitPhase(Phase.RECORDING)
        scenario.moveToState(Lifecycle.State.CREATED); assertEquals(1,RecordingRecovery.processOwned.count); assertEquals(Phase.IDLE,model.state.value.phase)
        scenario.moveToState(Lifecycle.State.RESUMED); assertEquals(0, server.requestCount)
    }
    @Test fun recreationRetainsSessionDraftButCancelsRecording() {
        key(); scenario.onActivity { model.edit("Session draft") }
        click("record"); waitPhase(Phase.RECORDING); scenario.recreate()
        waitPhase(Phase.RESULT); assertEquals("Session draft", model.state.value.text)
        assertEquals("synthetic-test-key",model.state.value.key); assertEquals(1,RecordingRecovery.processOwned.count); assertEquals(0,server.requestCount)
    }
    @Test fun backCancelsUploadAndIgnoresLateResponse() {
        val release=java.util.concurrent.CountDownLatch(1)
        server.dispatcher=object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest):MockResponse {
                release.await(30,TimeUnit.SECONDS)
                return MockResponse().setBody("{\"text\":\"late\"}")
            }
        }
        try {
            key(); click("record"); waitPhase(Phase.RECORDING); SystemClock.sleep(1500)
            click("stop"); waitPhase(Phase.TRANSCRIBING)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; waitPhase(Phase.IDLE); assertNoPendingAudio()
            release.countDown(); SystemClock.sleep(500)
            assertEquals("",model.state.value.text)
        } finally { release.countDown() }
    }
}
