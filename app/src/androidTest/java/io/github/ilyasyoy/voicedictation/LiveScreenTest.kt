package io.github.ilyasyoy.voicedictation

import io.github.ilyasyoy.voicedictation.session.*

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.ui.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual AudioRecord + actual WebSocket. Only the transcription service is synthetic. */
class LiveScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val bytes = AtomicInteger()
    private val disconnect = AtomicBoolean()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: DictationViewModel
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    } }
    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                private var partial = false
                private var committed = 0
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = JSONObject(text)
                    when (event.getString("type")) {
                        "session.update" -> {
                            val session = event.getJSONObject("session")
                            if (session.getJSONObject("audio").getJSONObject("input").getJSONObject("transcription").getString("model") != "gpt-live-transcribe") {
                                webSocket.send("{\"type\":\"error\",\"error\":{\"code\":\"invalid_model\"}}")
                            } else webSocket.send("{\"type\":\"session.updated\",\"session\":{\"type\":\"transcription\"}}")
                        }
                        "input_audio_buffer.append" -> {
                            bytes.addAndGet(Base64.getDecoder().decode(event.getString("audio")).size)
                            if (!partial) {
                                partial = true
                                val delta = "{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"turn-1\",\"event_id\":\"delta-1\",\"delta\":\"Live words\"}"
                                webSocket.send(delta); webSocket.send(delta) // Deliberate duplicate event.
                                if (disconnect.compareAndSet(true, false)) scheduler.schedule({ webSocket.close(1011, "synthetic server disconnect") }, 1500, TimeUnit.MILLISECONDS)
                            }
                        }
                        "input_audio_buffer.commit" -> {
                            committed++
                            val item = "turn-$committed"
                            val previous: Any = if (committed == 1) JSONObject.NULL else "turn-${committed - 1}"
                            webSocket.send(JSONObject().put("type", "input_audio_buffer.committed").put("item_id", item).put("previous_item_id", previous).toString())
                            val final = if (committed == 1) "Live words. Synthetic server result." else ""
                            scheduler.schedule({ webSocket.send(JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
                                .put("item_id", item).put("transcript", final).toString()) }, 5000, TimeUnit.MILLISECONDS)
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            })
        }
        server.start()
        val endpoint = server.url("/v1/realtime?intent=transcription").toString()
        shell("pm grant io.github.ilyasyoy.voicedictation ${Manifest.permission.RECORD_AUDIO}")
        MainActivity.testNotice = "TEST WEBSOCKET · synthetic text · no OpenAI call"
        MainActivity.testFactory = {
            DictationViewModel(AndroidAudioRecorder(it), OpenAiTranscriber(), AndroidPcmSource(),
                OpenAiRealtimeTranscriber(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), endpoint))
                .also { m -> model = m; m.setKey("synthetic-live-key") }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("mode_live")
    }
    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        MainActivity.testFactory = null; MainActivity.testNotice = null
        scheduler.shutdownNow(); server.shutdown()
    }
    private fun click(tag: String) {
        val configuration = tag in setOf("mode_record", "mode_live", "forget")
        if (configuration) compose.onNodeWithTag("open_settings").performScrollTo().performClick()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        if (configuration) compose.onNodeWithTag("close_settings").performScrollTo().performClick()
    }
    private fun waitFor(test: () -> Boolean) { compose.waitUntil(30_000, test) }
    private fun screenshot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(250)
        shell("mkdir -p /sdcard/Download/dictation-evidence")
        shell("screencap -p /sdcard/Download/dictation-evidence/$name.png")
    }
    @Test fun realPcmStreamsPartialThenFinalWithoutDuplicates() {
        click("record"); waitFor { model.state.value.livePartial }
        assertTrue(bytes.get() >= 4800); assertEquals("Live words", model.state.value.text)
        compose.onNodeWithTag("open_settings").assertIsNotEnabled()
        compose.onNodeWithTag("live_text_status").performScrollTo(); screenshot("07-live-partial")
        click("stop"); waitFor { model.state.value.phase == Phase.FINALIZING }
        screenshot("08-live-finishing")
        waitFor { model.state.value.phase == Phase.RESULT }
        assertEquals("Live words. Synthetic server result.", model.state.value.text)
        assertFalse(model.state.value.livePartial)
        compose.onNodeWithTag("live_text_status").performScrollTo(); screenshot("09-live-final")
        click("copy"); assertEquals(1, server.requestCount)
    }
    @Test fun disconnectKeepsPartialAndOnlyExplicitRecordReconnects() {
        disconnect.set(true); click("record"); waitFor { model.state.value.phase == Phase.ERROR }
        assertEquals(Problem.NETWORK, model.state.value.problem); assertEquals("Live words", model.state.value.text)
        screenshot("10-live-disconnected"); SystemClock.sleep(500); assertEquals(1, server.requestCount)
        click("record"); waitFor { model.state.value.phase == Phase.RECORDING && model.state.value.text == "Live words\nLive words" }; click("stop")
        waitFor { model.state.value.phase == Phase.RESULT }
        assertEquals(2, server.requestCount); assertTrue(model.state.value.text.startsWith("Live words\n"))
        assertTrue(model.state.value.livePartial) // Prior disconnected partial must not become falsely final.
    }
    @Test fun cancellationAndBackgroundReleaseLiveMicrophoneAndAllowModeSwitch() {
        click("record"); waitFor { model.state.value.livePartial }; click("cancel")
        assertFalse(model.state.value.audioBusy); val kept = model.state.value.text
        SystemClock.sleep(300); assertEquals(kept, model.state.value.text)
        click("mode_record"); assertEquals(Mode.RECORD, model.state.value.mode)
        click("mode_live"); click("record"); waitFor { model.state.value.phase == Phase.RECORDING }
        scenario.moveToState(Lifecycle.State.CREATED); assertFalse(model.state.value.audioBusy)
        scenario.moveToState(Lifecycle.State.RESUMED); assertEquals(2, server.requestCount)
    }
}
