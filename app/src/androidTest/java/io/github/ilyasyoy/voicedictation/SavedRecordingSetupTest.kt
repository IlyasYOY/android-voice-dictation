package io.github.ilyasyoy.voicedictation

import io.github.ilyasyoy.voicedictation.session.*

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Real Setup views with a suspended upload port; no audio capture, key or network call. */
class SavedRecordingSetupTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Test fun recreatedSetupObservesOldRetryCancellationAndReenablesActions() {
        val recovery=RecordingRecovery.processOwned
        val file=File.createTempFile("setup-retry-", ".m4a",instrumentation.targetContext.cacheDir).apply {writeText("synthetic fixture")}
        val cleanup=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        lateinit var retry:Job
        instrumentation.runOnMainSync {
            recovery.discard();recovery.retain(file)
            retry=scope.launch {recovery.retry {try {awaitCancellation()}finally {withContext(NonCancellable){cleanup.await()}}}}
        }
        try {
            ActivityScenario.launch(FloatingSetupActivity::class.java).use {scenario->
                scenario.onActivity {assertFalse(it.findViewById<Button>(R.id.floating_retry_audio).isEnabled)}
                scenario.recreate()
                scenario.onActivity {assertFalse(it.findViewById<Button>(R.id.floating_discard_audio).isEnabled)}
                retry.cancel();cleanup.complete(Unit)
                runBlocking {withTimeout(5000){retry.join()}}
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue(it.findViewById<Button>(R.id.floating_retry_audio).isEnabled)
                    assertTrue(it.findViewById<Button>(R.id.floating_discard_audio).isEnabled)
                }
                assertTrue(file.exists())
            }
        } finally {cleanup.complete(Unit);scope.cancel();instrumentation.runOnMainSync {recovery.discard()};file.delete()}
    }
    @Test fun explicitRetryFailureGuidanceShowsInsideSetupWhileAudioIsRetained() {
        val recovery=RecordingRecovery.processOwned
        val file=File.createTempFile("setup-guidance-", ".m4a",instrumentation.targetContext.cacheDir).apply {writeText("synthetic fixture")}
        instrumentation.runOnMainSync {recovery.discard();recovery.retain(file,RecordingRetryFailure.KEY)}
        try {
            ActivityScenario.launch(FloatingSetupActivity::class.java).use {scenario->
                for((failure,message) in listOf(RecordingRetryFailure.KEY to R.string.current_retry_key_error,
                    RecordingRetryFailure.AUTH to R.string.current_retry_key_error,RecordingRetryFailure.NETWORK to R.string.current_retry_network_error)) {
                    instrumentation.runOnMainSync {recovery.retain(file,failure)}
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertEquals(it.getString(message),it.findViewById<TextView>(R.id.floating_audio_problem).text.toString())
                        assertTrue(it.findViewById<Button>(R.id.floating_retry_audio).isEnabled)
                    }
                    assertTrue(file.exists())
                }
            }
        } finally {instrumentation.runOnMainSync {recovery.discard()};file.delete()}
    }
}
