package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingRepositoryTest {
    @get:Rule val temporary=TemporaryFolder()
    private val root get()=File(temporary.root,"records")
    private fun repository(commit:RecordingCommit=AtomicRecordingCommit)=
        RecordingRepository(root,{1234L},commit)
    private fun capture(store:RecordingRepository,bytes:String="FIRST|COMPLETE|LAST"):File = store.allocate().also {
        it.writeText(bytes);store.finalized(it,12)
    }
    @Test fun restartRestoresFinalizedAndInterruptedFilesWithoutAnUpload() {
        val first=repository();val stopped=capture(first);first.retain(stopped,SavedRecordingFailure.NETWORK)
        val interrupted=first.allocate().also {it.writeText("unfinished-container")}
        val uploading=capture(first);assertNotNull(first.beginUpload(uploading))
        val restored=repository()
        assertEquals(3,restored.pending().size)
        assertEquals(SavedRecordingStatus.FAILED,restored.record(stopped)?.status)
        assertEquals(SavedRecordingStatus.INTERRUPTED,restored.record(interrupted)?.status)
        assertEquals(SavedRecordingStatus.INTERRUPTED,restored.record(uploading)?.status)
        assertEquals("FIRST|COMPLETE|LAST",stopped.readText())
        assertEquals(12,restored.record(stopped)?.durationSeconds)
    }
    @Test fun interruptedResponseCommitPreservesThePreviousMetadataAndAllAudio() {
        var fail=false
        val store=repository(commit=RecordingCommit {target,values ->
            if(fail)throw IOException("injected before atomic replacement")
            AtomicRecordingCommit.write(target,values)
        })
        val audio=capture(store);val token=checkNotNull(store.beginUpload(audio));fail=true
        assertThrows(IOException::class.java) {store.complete(token,"durable words")}
        assertTrue(audio.exists())
        val restarted=repository()
        assertEquals(SavedRecordingStatus.INTERRUPTED,restarted.record(audio)?.status)
        assertEquals("",restarted.record(audio)?.transcript)
    }
    @Test fun successfulTranscriptAndAudioBothSurviveRestart() {
        val store=repository();val audio=capture(store);val token=checkNotNull(store.beginUpload(audio))
        assertTrue(store.complete(token,"Привет\nComplete words"));assertTrue(audio.exists())
        val restored=repository();val entry=restored.records().single()
        assertEquals(SavedRecordingStatus.COMPLETE,entry.status)
        assertEquals("Привет\nComplete words",entry.transcript)
        assertTrue(restored.pending().isEmpty());assertNull(restored.beginUpload(audio))
    }
    @Test fun explicitKeepPolicyDoesNotEvictSuccessfulAudioOrAnyPendingRecording() {
        val store=repository();val audio=capture(store)
        assertTrue(store.complete(checkNotNull(store.beginUpload(audio)),"words"))
        val pending=capture(store);store.retain(pending)
        val restored=repository()
        assertTrue(audio.exists());assertTrue(pending.exists());assertEquals(2,restored.records().size)
        assertEquals(1,restored.pending().size)
    }
    @Test fun cancelledAttemptCannotCommitOverAnExplicitNewRetryOrDiscard() {
        val store=repository();val audio=capture(store);val old=checkNotNull(store.beginUpload(audio))
        assertFalse(store.discard(old.id));assertNull(store.beginUpload(audio))
        assertTrue(store.failed(old,SavedRecordingFailure.NETWORK))
        val fresh=checkNotNull(store.beginUpload(audio));assertFalse(store.complete(old,"late old text"))
        assertTrue(store.complete(fresh,"fresh text"));assertTrue(store.discard(fresh.id))
        assertFalse(store.complete(fresh,"late after discard"));assertTrue(repository().records().isEmpty())
    }
    @Test fun concurrentClaimsSubmitEachFileAtMostOnce() {
        val store=repository();val audio=capture(store)
        val pool=Executors.newFixedThreadPool(4)
        try {assertEquals(1,pool.invokeAll((1..12).map {Callable {store.beginUpload(audio)}}).count {it.get()!=null})}
        finally {pool.shutdownNow()}
    }
    @Test fun legacyImportIsIdempotentAndNeverDeletesSourceBeforeMetadataCommit() {
        val cache=temporary.newFolder("legacy");val source=File(cache,"voice-legacy.m4a").apply {writeText("old complete audio")}
        val failing=repository(commit=RecordingCommit {_,_->throw IOException("injected")})
        assertThrows(IOException::class.java) {failing.migrateLegacy(cache)}
        assertTrue(source.exists())
        // Unknown partial directories remain intact; the complete legacy file is still importable.
        val store=repository();store.migrateLegacy(cache);store.migrateLegacy(cache)
        val migrated=store.records().single {it.legacyName==source.name}
        assertEquals("old complete audio",store.audio(migrated.id).readText())
        assertFalse(source.exists())
        assertEquals(1,store.records().count {it.legacyName==source.name})
    }
    @Test fun unknownMetadataIsPreservedAndNeverSubmittedOrSilentlySwept() {
        val store=repository();val audio=capture(store);val metadata=File(audio.parentFile,"record.properties")
        val p=Properties().apply {metadata.inputStream().use {load(it)};setProperty("format","999")}
        metadata.outputStream().use {p.store(it,null)}
        val restored=repository()
        assertEquals(SavedRecordingStatus.UNAVAILABLE,restored.record(audio)?.status)
        assertNull(restored.beginUpload(audio));assertTrue(audio.exists())
        assertTrue(metadata.readText().contains("999"))
        assertTrue(restored.discard(restored.records().single().id));assertFalse(audio.exists())
    }
    @Test fun metadataContainsOnlyTheClosedSchemaAndNoCredentialOrEditorState() {
        val store=repository();val audio=capture(store);store.complete(checkNotNull(store.beginUpload(audio)),"result")
        val properties=Properties().apply {File(audio.parentFile,"record.properties").inputStream().use {load(it)}}
        assertEquals(setOf("format","id","createdAt","updatedAt","status","seconds","transcript","attempt","kind","partial"),properties.stringPropertyNames())
        assertThrows(IllegalArgumentException::class.java) {store.audio("../../outside")}
    }
}
