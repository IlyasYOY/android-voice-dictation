package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Activity, MediaRecorder and HTTP client. Only the API server response is synthetic. */
class DictationScreenTest {
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
            DictationViewModel(AndroidAudioRecorder(it), OpenAiTranscriber(OkHttpClient.Builder()
                .retryOnConnectionFailure(false).build(), endpoint)).also { m -> model = m }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        MainActivity.testFactory = null; MainActivity.testNotice = null
        server.shutdown()
    }
    private fun key() { scenario.onActivity { model.setKey("synthetic-test-key") } }
    private fun waitPhase(phase: Phase) { compose.waitUntil(30_000) { model.state.value.phase == phase } }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun screenshot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(350)
        val out = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        shell("mkdir -p /sdcard/Download/dictation-evidence")
        shell("cp ${File(out, "$name.png").absolutePath} /sdcard/Download/dictation-evidence/$name.png")
    }
    private fun assertNoAudio() {
        assertTrue(File(context.cacheDir, "dictation").listFiles().orEmpty().isEmpty())
    }
    @Test fun recordUploadEditCopyAndClear() {
        screenshot("01-idle")
        compose.onNodeWithTag("record").assertIsNotEnabled()
        key()
        server.enqueue(MockResponse().setBody("{\"text\":\"A thought captured. Test server response.\"}").setBodyDelay(5, TimeUnit.SECONDS))
        click("record"); waitPhase(Phase.RECORDING); screenshot("02-recording")
        SystemClock.sleep(1800); click("stop"); waitPhase(Phase.TRANSCRIBING); screenshot("03-transcribing")
        waitPhase(Phase.RESULT); screenshot("04-result")
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.bodySize > 1000); assertEquals("Bearer synthetic-test-key", request.getHeader("Authorization"))
        compose.onNodeWithTag("transcript").performScrollTo().performTextReplacement("Edited thought")
        click("copy")
        scenario.onActivity { assertEquals("Edited thought", it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
        assertNoAudio(); click("clear"); assertEquals("", model.state.value.text)
        click("forget"); assertEquals("", model.state.value.key)
    }
    @Test fun serverErrorCanRetryThenDeletesAudio() {
        key(); server.enqueue(MockResponse().setResponseCode(500).setBody("private error"))
        server.enqueue(MockResponse().setBody("{\"text\":\"Recovered via explicit retry.\"}"))
        click("record"); waitPhase(Phase.RECORDING); SystemClock.sleep(1600); click("stop")
        waitPhase(Phase.ERROR); screenshot("05-error-retry"); assertTrue(model.state.value.canRetry)
        click("retry"); waitPhase(Phase.RESULT); assertEquals(2, server.requestCount); assertNoAudio()
    }
    @Test fun cancelAndBackgroundStopRealMicrophone() {
        key(); click("record"); waitPhase(Phase.RECORDING); click("cancel"); waitPhase(Phase.IDLE); assertNoAudio()
        click("record"); waitPhase(Phase.RECORDING)
        scenario.moveToState(Lifecycle.State.CREATED); assertNoAudio(); assertEquals(Phase.IDLE, model.state.value.phase)
        scenario.moveToState(Lifecycle.State.RESUMED); assertEquals(0, server.requestCount)
    }
    @Test fun recreationRetainsSessionDraftButCancelsRecording() {
        key(); scenario.onActivity { model.edit("Session draft") }
        click("record"); waitPhase(Phase.RECORDING); scenario.recreate()
        waitPhase(Phase.RESULT); assertEquals("Session draft", model.state.value.text)
        assertEquals("synthetic-test-key", model.state.value.key); assertNoAudio(); assertEquals(0, server.requestCount)
    }
    @Test fun backCancelsUploadAndIgnoresLateResponse() {
        key(); server.enqueue(MockResponse().setBody("{\"text\":\"late\"}").setBodyDelay(3, TimeUnit.SECONDS))
        click("record"); waitPhase(Phase.RECORDING); SystemClock.sleep(1500); click("stop"); waitPhase(Phase.TRANSCRIBING)
        device.pressBack(); waitPhase(Phase.IDLE); assertNoAudio(); SystemClock.sleep(3300)
        assertEquals("", model.state.value.text)
    }
}
