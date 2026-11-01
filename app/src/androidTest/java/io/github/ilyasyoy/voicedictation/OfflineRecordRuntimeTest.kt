package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/** Actual MediaRecorder/airplane UI; injected offline fault, manual retry through real localhost HTTP. */
class OfflineRecordRuntimeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString().trim()
    }
    private fun checkpoint(value:String)=instrumentation.sendStatus(2,Bundle().apply {putString("stream","\nCHECKPOINT: $value\n")})
    private fun waitFor(label:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30_000
        while(!condition() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
        assertTrue(label,condition())
    }
    private fun find(selector:BySelector):UiObject2 {
        repeat(8) {
            device.findObject(selector)?.let {return it}
            device.findObject(By.text("Advanced"))?.click()
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.65f)
            SystemClock.sleep(150)
        }
        return checkNotNull(device.wait(Until.findObject(selector),10_000)) {"Missing $selector"}
    }
    private fun screenshot(name:String) {
        shell("mkdir -p /sdcard/Download/vd003")
        shell("screencap -p /sdcard/Download/vd003/$name.png")
    }
    private fun airplane(enabled:Boolean) {
        val before=shell("settings get global airplane_mode_on")=="1"
        if(before==enabled)return
        context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        find(By.text(Pattern.compile("(?i)Airplane mode"))).click()
        waitFor("Airplane mode must actually change through system UI") { (shell("settings get global airplane_mode_on")=="1")==enabled }
    }
    @Test fun immediateAirplaneCaptureSurvivesNetworkLossAndSettingsThenUploadsOnlyOnManualRetry() {
        val idle=Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout=0
        val originalPlane=shell("settings get global airplane_mode_on")=="1"
        val oldScale=shell("settings get global animator_duration_scale")
        val server=MockWebServer()
        server.enqueue(MockResponse().setBody("{\"text\":\"Recorded offline. Uploaded by explicit retry.\"}"))
        server.start()
        val keyRead=CompletableDeferred<Unit>()
        val calls=AtomicInteger();val startedAt=AtomicLong();val startCost=AtomicLong();var tapAt=0L
        val capturing=AtomicBoolean();val captured=AtomicReference(ByteArray(0))
        lateinit var model:DictationViewModel
        val saved=AndroidApiKeyStore(context,".test.crossapp")
        try {
            shell("settings put global animator_duration_scale 0")
            shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            shell("cmd statusbar collapse")
            device.pressHome()
            checkpoint("Enabling isolated synthetic fixture")
            context.startActivity(Intent(context,DebugFloatingSetupActivity::class.java).putExtra("port",server.port).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            find(By.text(Pattern.compile("(?i)Enable isolated fixture mode"))).click()
            waitFor("Synthetic key is saved") {runBlocking {saved.load()!=null}}
            checkpoint("Switching real system airplane mode on")
            airplane(true)
            MainActivity.testNotice="OFFLINE TEST · synthetic server · no OpenAI"
            MainActivity.testFactory={ owner ->
                val recorder=AndroidAudioRecorder(owner)
                DictationViewModel(object:AudioRecorder {
                    override fun start(onLimit:()->Unit) {
                        val before=SystemClock.elapsedRealtime();recorder.start(onLimit)
                        val ready=SystemClock.elapsedRealtime();startedAt.set(ready);startCost.set(ready-before);capturing.set(true)
                    }
                    override fun stop()=recorder.stop().also {
                        captured.set(it.readBytes());capturing.set(false)
                        checkpoint("Local recorder finalized ${it.length()} bytes without uploading")
                    }
                    override fun cancel() {recorder.cancel();capturing.set(false)}
                },Transcriber {_,_->
                    calls.incrementAndGet()
                    throw java.io.IOException("Injected offline transport; real airplane mode does not disable loopback")
                },keyStore=object:ApiKeyStore {
                    override suspend fun load():String? {keyRead.await();return saved.load()}
                    override suspend fun save(key:String)=saved.save(key)
                    override suspend fun forget()=saved.forget()
                },recordings=StoredRecordingRetention(AndroidRecordingStorage.repository(owner))).also {model=it}
            }
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            checkpoint("Opening main Record control while saved-key read is suspended")
            // Wait for the cold main window before the generic finder can scroll the old Settings window.
            checkNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.record))), 60_000))
            screenshot("01-airplane-idle-before-key-read")
            val recordControl=find(By.desc(context.getString(R.string.record)))
            tapAt=SystemClock.elapsedRealtime();recordControl.click()
            waitFor("Local capture starts while key loading is suspended") {capturing.get() && model.state.value.phase==Phase.RECORDING}
            assertTrue(model.state.value.keyBusy);assertEquals(0,calls.get());assertEquals(0,server.requestCount)
            checkpoint("MediaRecorder started ${startedAt.get()-tapAt} ms after click injection (${startCost.get()} ms inside recorder.start); saved-key read still blocked; zero pre-Stop requests")
            screenshot("02-airplane-local-capture")
            if(InstrumentationRegistry.getArguments().getString("motionCapture")=="true") {
                shell("settings put global animator_duration_scale 1");SystemClock.sleep(4000)
                shell("settings put global animator_duration_scale 0")
            } else SystemClock.sleep(1500)
            checkpoint("Stopping actual local capture before releasing the key read")
            find(By.desc(context.getString(R.string.stop))).click()
            waitFor("Stop releases microphone before credential read/upload") {!capturing.get() && captured.get().isNotEmpty() && model.state.value.phase==Phase.TRANSCRIBING}
            assertEquals(0,calls.get());assertTrue(captured.get().isNotEmpty());screenshot("03-stopped-before-key-read")
            keyRead.complete(Unit)
            waitFor("Injected transport loss retains retry audio") {model.state.value.canRetry}
            assertEquals(Problem.NETWORK,model.state.value.problem);assertEquals(1,calls.get())
            screenshot("04-network-loss-recording-retained")
            airplane(false) // Leaving the Activity must retain the stopped file and release old ownership.
            waitFor("Activity stop transfers audio to process recovery") {RecordingRecovery.processOwned.count==1}
            SystemClock.sleep(2000);assertEquals(1,calls.get());assertEquals(0,server.requestCount)
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            find(By.descContains(context.getString(R.string.history_open))).click()
            find(By.desc(context.getString(R.string.history_retry)))
            screenshot("05-manual-recovery-after-network-restored")
            assertEquals(0,server.requestCount)
            find(By.desc(context.getString(R.string.history_retry))).click()
            waitFor("Only explicit Retry completes the retained upload") {RecordingRecovery.processOwned.count==0 && FloatingFeedback.processOwned.text.isNotBlank()}
            assertEquals(1,server.requestCount)
            assertEquals("Recorded offline. Uploaded by explicit retry.",FloatingFeedback.processOwned.text)
            val retried=checkNotNull(server.takeRequest(5,TimeUnit.SECONDS))
            fun contains(bytes:ByteArray,sample:ByteArray):Boolean=(0..bytes.size-sample.size).any {start->sample.indices.all {bytes[start+it]==sample[it]}}
            assertTrue("Explicit manual HTTP upload contains every original audio byte",contains(retried.body.readByteArray(),captured.get()))
            screenshot("06-explicit-retry-passive-result-no-insertion")
            checkpoint("Actual airplane capture + real stopped file + injected offline failure + Settings/background preservation + one manual retry passed; audio bytes=${captured.get().size}; real spoken first word is not tested")
            SystemClock.sleep(3000)
        } finally {
            keyRead.complete(Unit)
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach {it.finish()}}
            val end=SystemClock.elapsedRealtime()+10_000
            while(RecordingRecovery.processOwned.retrying && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
            instrumentation.runOnMainSync {RecordingRecovery.processOwned.discard();FloatingFeedback.processOwned.discard()}
            MainActivity.testFactory=null;MainActivity.testNotice=null
            airplane(originalPlane)
            runBlocking {saved.forget()}
            context.getSharedPreferences("floating_fixture",0).edit().clear().apply()
            if(oldScale=="null")shell("settings delete global animator_duration_scale")else shell("settings put global animator_duration_scale $oldScale")
            server.shutdown();Configurator.getInstance().waitForIdleTimeout=idle
        }
    }
}
