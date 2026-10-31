package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StoredRecordingSessionTest {
    @get:Rule val temporary=TemporaryFolder()
    private val root get()=File(temporary.root,"recordings")
    private fun store(commit:RecordingCommit=AtomicRecordingCommit)=RecordingRepository(root,commit=commit)
    private class Capture(private val store:RecordingRepository):AudioRecorder {
        lateinit var audio:File
        var capturing=false
        override fun start(onLimit:()->Unit) {audio=store.allocate();audio.writeText("FIRST|");capturing=true}
        override fun stop():File {audio.appendText("LAST");capturing=false;store.finalized(audio,2);return audio}
        override fun cancel() {capturing=false}
    }
    @Test fun successKeepsAudioAndTranscriptWithNoCredentialMetadata()=runTest {
        val store=store();val capture=Capture(store);var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {audio,key ->
            calls++;assertEquals("synthetic-key",key);assertEquals("FIRST|LAST",audio.readText());"Saved words"
        },recordings=StoredRecordingRetention(store))
        session.setKey("synthetic-key");session.start(true);assertEquals(0,calls)
        session.stop();runCurrent();assertEquals(Phase.RESULT,session.state.value.phase)
        session.close()
        val restored=store();assertEquals("Saved words",restored.records().single().transcript)
        assertEquals("FIRST|LAST",capture.audio.readText());assertTrue(restored.pending().isEmpty())
        assertFalse(File(capture.audio.parentFile,"record.properties").readText().contains("synthetic-key"))
    }
    @Test fun failedStoppedFileTransfersAndRestoresForOnlyExplicitRetry()=runTest {
        val store=store();val capture=Capture(store);var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->calls++;throw IOException("offline")},recordings=StoredRecordingRetention(store))
        session.setKey("synthetic-key");session.start(true);session.stop();runCurrent()
        assertEquals(1,calls);assertTrue(session.state.value.canRetry)
        val file=checkNotNull(session.takeRecordingForRecovery());store.retain(file,SavedRecordingFailure.NETWORK);session.close()
        val restored=store();assertEquals(1,calls);assertEquals(1,restored.pending().size)
        val token=checkNotNull(restored.beginUpload(file));calls++
        assertTrue(restored.complete(token,"Manual result"));assertEquals(2,calls)
        assertTrue(file.exists());assertEquals("Manual result",store().records().single().transcript)
    }
    @Test fun failureBeforeSubmissionMakesNoNetworkCallAndCloseStillClearsCredentials()=runTest {
        var failed=false
        val store=store(RecordingCommit {target,values ->
            if(failed)throw IOException("disk full")
            AtomicRecordingCommit.write(target,values)
        })
        val capture=Capture(store);var calls=0
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->calls++;"unused"},recordings=StoredRecordingRetention(store))
        session.setKey("synthetic-key");session.start(true);session.stop();failed=true;runCurrent()
        assertEquals(0,calls);assertEquals(Problem.LOCAL_STORAGE,session.state.value.problem)
        assertTrue(capture.audio.exists());session.close()
        assertEquals("",session.state.value.key);assertFalse(capture.capturing)
        assertTrue(capture.audio.exists());assertEquals(1,store().pending().size)
    }
    @Test fun failedResponseCommitKeepsReceivedWordsAndAudioInsteadOfLosingEither()=runTest {
        var failed=false
        val store=store(RecordingCommit {target,values ->
            if(failed)throw IOException("disk full")
            AtomicRecordingCommit.write(target,values)
        })
        val capture=Capture(store)
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->failed=true;"Words remain copyable"},recordings=StoredRecordingRetention(store))
        session.setKey("synthetic-key");session.start(true);session.stop();runCurrent()
        assertEquals(Problem.LOCAL_STORAGE,session.state.value.problem)
        assertEquals("Words remain copyable",session.state.value.text)
        assertTrue(capture.audio.exists());session.close()
        assertEquals(SavedRecordingStatus.INTERRUPTED,store().records().single().status)
    }
}
