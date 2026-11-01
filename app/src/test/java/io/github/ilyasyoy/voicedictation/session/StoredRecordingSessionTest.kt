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
    @Test fun oneExplicitRetryAfterDiskRecoverySavesReceivedResponseWithoutNewNetwork()=runTest {
        var failed=false;var calls=0
        val store=store(RecordingCommit {target,values ->
            if(failed)throw IOException("disk full")
            AtomicRecordingCommit.write(target,values)
        })
        val capture=Capture(store)
        val session=DictationSession(backgroundScope,capture,Transcriber {_,_->calls++;failed=true;"Known paid response"},recordings=StoredRecordingRetention(store))
        session.setKey("synthetic-key");session.start(true);session.stop();runCurrent()
        assertEquals(Problem.LOCAL_STORAGE,session.state.value.problem);assertEquals(1,calls)
        failed=false;session.retry();runCurrent()
        assertEquals(Phase.RESULT,session.state.value.phase);assertEquals(1,calls)
        assertEquals("Known paid response",store().records().single().transcript)
        assertTrue(capture.audio.exists());session.close()
    }
    @Test fun deletedSourceCannotBeResurrectedByRepeatedBackgroundCheckpoints()=runTest {
        val store=store();val audio=store.allocate().apply {writeText("audio")};store.finalized(audio)
        store.complete(checkNotNull(store.beginUpload(audio)),"Deleted words")
        val id=checkNotNull(store.record(audio)).id
        store.saveText(RecordingTextSlot.MAIN,"Deleted words",id)
        val session=DictationSession(backgroundScope,Capture(store),Transcriber {_,_->error("no upload")},recordings=StoredRecordingRetention(store,true))
        assertEquals("Deleted words",session.state.value.text)
        assertTrue(store.discard(id))
        repeat(2) {session.cancel()}
        assertEquals("",store().savedText(RecordingTextSlot.MAIN))
        session.refreshDraft();assertEquals("",session.state.value.text)
        session.edit("A new deliberate draft")
        assertEquals("A new deliberate draft",store().savedText(RecordingTextSlot.MAIN))
        session.close()
    }
    @Test fun restoredMainDraftKeepsItsDeletionLinkThroughBackgroundAndClose()=runTest {
        val first=store();val audio=first.allocate().apply {writeText("audio")};first.finalized(audio)
        first.complete(checkNotNull(first.beginUpload(audio)),"Saved words")
        val id=checkNotNull(first.record(audio)).id
        first.saveText(RecordingTextSlot.MAIN,"Saved words",id)
        val restored=store()
        val session=DictationSession(backgroundScope,Capture(restored),Transcriber {_,_->error("no upload")},recordings=StoredRecordingRetention(restored,true))
        assertEquals("Saved words",session.state.value.text)
        session.cancel() // Main opens Saved recordings; background checkpoints the restored draft.
        assertEquals(id,restored.savedDraft(RecordingTextSlot.MAIN).sourceId)
        assertTrue(restored.discard(id));session.refreshDraft()
        assertEquals("",session.state.value.text);session.close()
        assertEquals("",store().savedText(RecordingTextSlot.MAIN));assertFalse(audio.exists())
    }

    @Test fun temporaryEmptyEditKeepsItsSourceAndDeleteCannotLeaveReplacementOrphan()=runTest {
        val store=store()
        val audio=store.allocate().apply {writeText("audio")};store.finalized(audio)
        store.complete(checkNotNull(store.beginUpload(audio)),"Original")
        val id=checkNotNull(store.record(audio)).id
        store.saveText(RecordingTextSlot.MAIN,"Original",id)
        val session=DictationSession(backgroundScope,Capture(store),Transcriber {_,_->error("no upload")},recordings=StoredRecordingRetention(store,true))
        session.edit("");assertEquals(id,store.savedDraft(RecordingTextSlot.MAIN).sourceId)
        session.edit("Replacement");assertEquals(id,store.savedDraft(RecordingTextSlot.MAIN).sourceId)
        assertTrue(store.discard(id));session.refreshDraft();session.cancel();session.close()
        assertEquals("",store().savedText(RecordingTextSlot.MAIN))
        assertEquals("",session.state.value.text)

        val second=store.allocate().apply {writeText("audio")};store.finalized(second)
        store.complete(checkNotNull(store.beginUpload(second)),"Keep this recording")
        val secondId=checkNotNull(store.record(second)).id
        store.saveText(RecordingTextSlot.MAIN,"Keep this recording",secondId)
        val owner=DictationSession(backgroundScope,Capture(store),Transcriber {_,_->error("no upload")},recordings=StoredRecordingRetention(store,true))
        owner.clear()
        assertNull(store.savedDraft(RecordingTextSlot.MAIN).sourceId)
        assertNotNull(store.record(secondId));owner.close()
    }

    @Test fun failedDraftCommitSurvivesRefreshButConfirmedSourceDeletionStillInvalidatesIt()=runTest {
        var failDraft=false
        val store=store(RecordingCommit {target,values ->
            if(failDraft && target.name=="main-text.properties")throw IOException("synthetic draft failure")
            AtomicRecordingCommit.write(target,values)
        })
        val audio=store.allocate().apply {writeText("audio")};store.finalized(audio)
        store.complete(checkNotNull(store.beginUpload(audio)),"Saved")
        val id=checkNotNull(store.record(audio)).id
        store.saveText(RecordingTextSlot.MAIN,"Saved",id)
        val session=DictationSession(backgroundScope,Capture(store),Transcriber {_,_->error("no upload")},recordings=StoredRecordingRetention(store,true))
        failDraft=true;session.edit("Unsaved but copyable")
        assertEquals(Problem.LOCAL_STORAGE,session.state.value.problem)
        assertEquals("Saved",store.savedText(RecordingTextSlot.MAIN))
        repeat(2) {session.refreshDraft();session.cancel()}
        assertEquals("Unsaved but copyable",session.state.value.text)
        assertEquals(Problem.LOCAL_STORAGE,session.state.value.problem)
        failDraft=false
        assertTrue(store.discard(id));session.refreshDraft();session.cancel();session.close()
        assertEquals("",session.state.value.text)
        assertEquals("",store().savedText(RecordingTextSlot.MAIN))
    }
}
