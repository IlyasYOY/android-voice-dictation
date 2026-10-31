package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Native UI smoke with animations enabled; Compose idling is deliberately not used for a flowing surface. */
class MotionSmokeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun shell(cmd: String)=instrumentation.uiAutomation.executeShellCommand(cmd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString().trim() }
    private fun checkpoint(text: String) = instrumentation.sendStatus(2,Bundle().apply { putString("stream","\nCHECKPOINT: $text\n") })
    private fun screenshot(name: String) { shell("mkdir -p /sdcard/Download/soft-current-motion"); shell("screencap -p /sdcard/Download/soft-current-motion/$name.png") }
    private fun waitFor(predicate: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+30_000
        while (!predicate() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100)
        assertTrue(predicate())
    }
    @Test fun flowingShapeShowsRealConnectingCaptureAndResultWithoutSpinnerOrReports() {
        dismissStaleSystemUiAnr(instrumentation)
        val idleTimeout=Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout=0
        val oldScale=shell("settings get global animator_duration_scale")
        val bytes=AtomicInteger()
        val scheduler=Executors.newSingleThreadScheduledExecutor()
        val server=MockWebServer()
        fun response()=MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            private var sent=false
            override fun onMessage(webSocket: WebSocket,text: String) {
                val event=JSONObject(text)
                when (event.getString("type")) {
                    "session.update" -> scheduler.schedule({ webSocket.send("{\"type\":\"session.updated\",\"session\":{\"type\":\"transcription\"}}") },3,TimeUnit.SECONDS)
                    "input_audio_buffer.append" -> {
                        bytes.addAndGet(Base64.getDecoder().decode(event.getString("audio")).size)
                        if (!sent) { sent=true; webSocket.send("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"one\",\"delta\":\"Soft flowing words\"}") }
                    }
                    "input_audio_buffer.commit" -> {
                        webSocket.send("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"one\",\"previous_item_id\":null}")
                        scheduler.schedule({ webSocket.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"one\",\"transcript\":\"Soft flowing words. Synthetic server result.\"}") },1,TimeUnit.SECONDS)
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,null) }
        })
        server.enqueue(response())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(response())
        server.start()
        val endpoint=server.url("/v1/realtime?intent=transcription").toString()
        lateinit var model: DictationViewModel
        MainActivity.testNotice="MOTION TEST · synthetic words · localhost only"
        MainActivity.testFactory={ context -> DictationViewModel(AndroidAudioRecorder(context),OpenAiTranscriber(),AndroidPcmSource(),
            OpenAiRealtimeTranscriber(OkHttpClient.Builder().retryOnConnectionFailure(false).build(),endpoint))
            .also { model=it; it.setKey("synthetic-motion-key") } }
        try {
            shell("settings put global animator_duration_scale 1")
            shell("pm grant io.github.ilyasyoy.voicedictation ${Manifest.permission.RECORD_AUDIO}")
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            checkNotNull(device.wait(Until.findObject(By.desc("Record")),30_000))
            checkpoint("Shared vector idle motion visible")
            screenshot("01-idle-flow"); SystemClock.sleep(4000); screenshot("02-idle-flow-later")
            checkNotNull(device.findObject(By.text("Live"))).click()
            waitFor { model.state.value.mode==Mode.LIVE }
            checkNotNull(device.findObject(By.desc("Record"))).click()
            waitFor { model.state.value.phase==Phase.CONNECTING }
            assertEquals(0,bytes.get()); screenshot("03-connecting-microphone-off")
            waitFor { model.state.value.phase==Phase.RECORDING && bytes.get()>0 }
            checkpoint("Real PCM capture active; faster purple shape motion")
            screenshot("04-listening-flow"); SystemClock.sleep(5000); screenshot("05-listening-flow-later")
            checkNotNull(device.wait(Until.findObject(By.desc("Stop")),10_000)).click()
            waitFor { model.state.value.phase==Phase.RESULT }
            assertEquals("Soft flowing words. Synthetic server result.",model.state.value.text)
            screenshot("06-result-no-report-labels")
            checkNotNull(device.findObject(By.desc("Record"))).click()
            waitFor { model.state.value.phase==Phase.ERROR }
            screenshot("08-definite-network-failure-red")
            assertEquals(2,server.requestCount)
            SystemClock.sleep(2000)
            assertEquals("No automatic retry",2,server.requestCount)
            val beforeRetry=bytes.get()
            checkNotNull(device.findObject(By.desc("Record"))).click()
            waitFor { model.state.value.phase==Phase.RECORDING && bytes.get()>beforeRetry }
            screenshot("09-explicit-retry-purple")
            SystemClock.sleep(3000)
            checkNotNull(device.findObject(By.desc("Stop"))).click()
            waitFor { model.state.value.phase==Phase.RESULT }
            assertEquals(3,server.requestCount)

            assertNull(device.findObject(By.text("Your words are ready")))
            assertNull(device.findObject(By.textContains("Confirmed segments")))
            SystemClock.sleep(3000)
            checkNotNull(device.findObject(By.desc("Settings"))).click()
            checkNotNull(device.wait(Until.findObject(By.text("Settings")),10_000))
            screenshot("07-settings"); SystemClock.sleep(3000)
            checkpoint("Native motion smoke complete; PCM bytes=${bytes.get()}")
        } finally {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() } }
            MainActivity.testFactory=null; MainActivity.testNotice=null
            if (oldScale=="null") shell("settings delete global animator_duration_scale") else shell("settings put global animator_duration_scale $oldScale")
            scheduler.shutdownNow(); server.shutdown()
            Configurator.getInstance().waitForIdleTimeout=idleTimeout
        }
    }
}
