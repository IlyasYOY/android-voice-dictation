package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class ImmediateRecordTest {
    private class Capture : AudioRecorder {
        val audio=File.createTempFile("immediate-record-", ".m4a")
        var starts=0;var stops=0;var capturing=false
        override fun start(onLimit:()->Unit) {starts++;capturing=true;audio.writeText("FIRST_SAMPLE|")}
        override fun stop():File {stops++;capturing=false;audio.appendText("LAST_SAMPLE");return audio}
        override fun cancel() {if(capturing)audio.delete();capturing=false}
    }
    private fun store(load: suspend ()->String?)=object:ApiKeyStore {
        override suspend fun load()=load()
        override suspend fun save(key:String)=Unit
        override suspend fun forget()=Unit
    }
    @Test fun firstSampleIsCapturedBeforeSuspendedKeyReadAndNoNetworkRunsBeforeStop()=runTest {
        val key=CompletableDeferred<String?>();val capture=Capture();var calls=0;var uploaded=""
        val session=DictationSession(backgroundScope,capture,Transcriber {file,credential->
            calls++;assertEquals("synthetic-key",credential);uploaded=file.readText();"Words"
        },keyStore=store {key.await()})
        try {
            runCurrent();assertTrue(session.state.value.keyBusy)
            session.start(true)
            assertEquals(Phase.RECORDING,session.state.value.phase);assertTrue(capture.capturing)
            assertEquals("FIRST_SAMPLE|",capture.audio.readText());assertEquals(0,calls)
            session.stop();runCurrent()
            assertFalse(capture.capturing);assertEquals(Phase.TRANSCRIBING,session.state.value.phase);assertEquals(0,calls)
            key.complete("synthetic-key");runCurrent()
            assertEquals(1,calls);assertEquals("FIRST_SAMPLE|LAST_SAMPLE",uploaded)
            assertEquals(Phase.RESULT,session.state.value.phase);assertFalse(capture.audio.exists())
        } finally {session.close();capture.audio.delete()}
    }
    @Test fun missingKeyDoesNotPreventLocalCaptureAndAddingKeyDoesNotUploadUntilExplicitRetry()=runTest {
        val capture=Capture();var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {file,_->calls++;assertEquals("FIRST_SAMPLE|LAST_SAMPLE",file.readText());"Words"})
        try {
            session.start(true);assertEquals(Phase.RECORDING,session.state.value.phase)
            session.stop();runCurrent()
            assertEquals(0,calls);assertEquals(Problem.KEY,session.state.value.problem)
            assertTrue(session.state.value.canRetry);assertTrue(capture.audio.exists())
            session.setKey("synthetic-key");runCurrent();assertEquals(0,calls)
            repeat(3){session.retry()};runCurrent()
            assertEquals(1,calls);assertFalse(capture.audio.exists())
        } finally {session.close();capture.audio.delete()}
    }
    @Test fun storageFailureDuringCaptureDoesNotFalselyStopRecordingOrLoseAudio()=runTest {
        val key=CompletableDeferred<String?>();val capture=Capture();var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->calls++;"unused"},keyStore=store {key.await()})
        try {
            runCurrent();session.start(true);key.completeExceptionally(IOException("storage unavailable"));runCurrent()
            assertEquals(Phase.RECORDING,session.state.value.phase);assertTrue(capture.capturing)
            assertEquals(Problem.STORAGE,session.state.value.problem)
            session.stop();runCurrent()
            assertEquals(0,calls);assertFalse(capture.capturing);assertTrue(session.state.value.canRetry)
            assertTrue(capture.audio.exists());assertEquals(Problem.STORAGE,session.state.value.problem)
        } finally {session.close();capture.audio.delete()}
    }
    @Test fun offlineFailurePreservesAllSamplesAndRestoringNetworkDoesNotAutomaticallyRetry()=runTest {
        val capture=Capture();var online=false;var calls=0;val uploads=mutableListOf<String>()
        val session=DictationSession(backgroundScope,capture,Transcriber {file,_->
            calls++;uploads+=file.readText();if(!online)throw IOException("offline");"Words"
        })
        try {
            session.setKey("synthetic-key");repeat(3){session.start(true)}
            assertEquals(1,capture.starts);assertEquals(0,calls)
            repeat(3){session.stop()};runCurrent()
            assertEquals(1,calls);assertTrue(session.state.value.canRetry);assertTrue(capture.audio.exists())
            repeat(3){session.start(true)} // A caller cannot silently replace an owned failed clip.
            assertEquals(1,capture.starts);assertTrue(capture.audio.exists())
            online=true;advanceTimeBy(1000);runCurrent();assertEquals(1,calls)
            repeat(3){session.retry()};runCurrent()
            assertEquals(2,calls);assertEquals(listOf("FIRST_SAMPLE|LAST_SAMPLE","FIRST_SAMPLE|LAST_SAMPLE"),uploads)
            assertFalse(capture.audio.exists());assertEquals(Phase.RESULT,session.state.value.phase)
        } finally {session.close();capture.audio.delete()}
    }
    @Test fun backgroundFinalizesLocallyWithoutUploadingAndTransfersOnlyTheFile()=runTest {
        val capture=Capture();var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->calls++;"unused"})
        val recovery=RecordingRecovery()
        try {
            session.start(true)
            val file=checkNotNull(session.takeRecordingForRecovery());recovery.retain(file);session.close();runCurrent()
            assertEquals(0,calls);assertFalse(capture.capturing);assertEquals(1,capture.stops)
            assertEquals("FIRST_SAMPLE|LAST_SAMPLE",file.readText());assertEquals("",session.state.value.key)
            assertEquals(1,recovery.count)
            assertEquals("Manual",recovery.retry {assertEquals(file,it);calls++;"Manual"})
            assertEquals(1,calls);assertFalse(file.exists())
        } finally {session.close();recovery.discard();capture.audio.delete()}
    }
    @Test fun backgroundDuringStoppedUploadCancelsLateResultButKeepsOriginalSamplesForManualRetry()=runTest {
        val capture=Capture();lateinit var response:Continuation<String>
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->suspendCoroutine {response=it}})
        try {
            session.setKey("synthetic-key");session.start(true);session.stop();runCurrent()
            val file=checkNotNull(session.takeRecordingForRecovery());session.close()
            response.resume("late result");runCurrent()
            assertTrue(file.exists());assertEquals("FIRST_SAMPLE|LAST_SAMPLE",file.readText())
            assertEquals("",session.state.value.text);assertEquals("",session.state.value.key)
        } finally {session.close();capture.audio.delete()}
    }
    @Test fun cancelledStoppedRequestCannotUploadWhenItsDelayedKeyEventuallyArrives()=runTest {
        val key=CompletableDeferred<String?>();val capture=Capture();var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->calls++;"unused"},keyStore=store {key.await()})
        try {
            runCurrent();session.start(true);session.stop();runCurrent()
            val file=checkNotNull(session.takeRecordingForRecovery());session.close()
            key.complete("synthetic-key");runCurrent()
            assertEquals(0,calls);assertTrue(file.exists())
        } finally {session.close();capture.audio.delete()}
    }
}
