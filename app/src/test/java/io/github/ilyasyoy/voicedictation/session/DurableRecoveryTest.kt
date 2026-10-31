package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DurableRecoveryTest {
    @get:Rule val temporary=TemporaryFolder()
    private val root get()=File(temporary.root,"records")
    private fun audio(store:RecordingRepository)=store.allocate().also {it.writeText("FIRST|LAST");store.finalized(it);store.retain(it)}
    @Test fun restoredOwnerSendsNothingUntilExplicitRetryAndKeepsSuccessfulAudio()=runTest {
        val first=RecordingRepository(root);val audio=audio(first)
        val restored=RecordingRepository(root);val owner=RecordingRecovery();owner.attach(restored);var calls=0
        runCurrent();assertEquals(0,calls);assertEquals(1,owner.count)
        assertEquals("Recovered",owner.retry {calls++;assertEquals("FIRST|LAST",it.readText());"Recovered"})
        assertEquals(1,calls);assertEquals(0,owner.count);assertEquals(1,owner.state.value.savedCount)
        assertTrue(audio.exists());assertEquals("Recovered",RecordingRepository(root).records().single().transcript)
    }
    @Test fun cancelledUploadCannotCommitALateResultOrDuplicateAnotherTap()=runTest {
        val store=RecordingRepository(root);val audio=audio(store);val owner=RecordingRecovery();owner.attach(store)
        val response=CompletableDeferred<String>();var calls=0
        val job=launch {owner.retry {calls++;response.await()}}
        runCurrent();assertNull(owner.retry {calls++;"duplicate"});assertEquals(1,calls)
        job.cancelAndJoin();response.complete("late");runCurrent()
        assertEquals(1,owner.count);assertTrue(audio.exists());assertEquals("",store.records().single().transcript)
        assertEquals(1,RecordingRepository(root).pending().size)
    }
    @Test fun failedLocalResultCommitCanBeRetriedWithoutPayingForAnotherUpload()=runTest {
        var fail=false
        val store=RecordingRepository(root,commit=RecordingCommit {target,properties ->
            if(fail)throw IOException("injected full storage")
            AtomicRecordingCommit.write(target,properties)
        })
        val audio=audio(store);val owner=RecordingRecovery();owner.attach(store);var calls=0
        try {owner.retry {calls++;fail=true;"Paid response kept"};fail("must expose storage failure")}
        catch(_:RecordingStorageFailure){}
        assertEquals("Paid response kept",owner.state.value.receivedTexts.values.single())
        assertEquals(1,owner.count);assertTrue(audio.exists())
        fail=false
        assertEquals("Paid response kept",owner.retry {calls++;"unwanted paid retry"})
        assertEquals(1,calls);assertTrue(audio.exists());assertEquals(0,owner.count)
        assertTrue(owner.state.value.receivedTexts.isEmpty())
    }
    @Test fun deletingOneRecordClearsItsLinkedDraftButPreservesUnrelatedData() {
        val store=RecordingRepository(root);val audio=audio(store)
        store.complete(checkNotNull(store.beginUpload(audio)),"result")
        val id=checkNotNull(store.record(audio)).id
        store.saveText(RecordingTextSlot.MAIN,"edited current text",id)
        val other=audio(store)
        assertTrue(store.discard(id));assertEquals("",store.savedText(RecordingTextSlot.MAIN))
        assertFalse(audio.exists());assertEquals("FIRST|LAST",other.readText())
        assertEquals(1,RecordingRepository(root).records().size)
    }
    @Test fun failedDeleteCommitNeverRemovesPendingBytes() {
        var fail=false
        val store=RecordingRepository(root,commit=RecordingCommit {target,properties ->
            if(fail)throw IOException("injected")
            AtomicRecordingCommit.write(target,properties)
        })
        val audio=audio(store);val owner=RecordingRecovery();owner.attach(store);fail=true
        assertFalse(owner.discard(checkNotNull(store.record(audio)).id))
        assertTrue(audio.exists());assertEquals(1,RecordingRepository(root).pending().size)
    }
}
