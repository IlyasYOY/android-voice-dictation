package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.util.Properties
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Raw old-format bytes, not a production text-capture writer. */
class LegacyTextUpgradeTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun legacy(root:File,text:String="historical words",partial:Boolean=false,status:String="COMPLETE"):String {
        val id=UUID.randomUUID().toString();val folder=File(root,id).apply {mkdirs()}
        val p=Properties().apply {
            setProperty("format","1");setProperty("id",id);setProperty("createdAt","1");setProperty("updatedAt","2")
            setProperty("status",status);setProperty("seconds","5");setProperty("transcript",text)
            setProperty("attempt","0");setProperty("kind","LIVE");setProperty("partial",partial.toString())
        }
        File(folder,"record.properties").outputStream().use {p.store(it,null)}
        return id
    }
    @Test fun oldTextAndLinkedDraftRemainReadableAcrossUpgradeAndRestartWithoutFakeAudio() {
        val root=temporary.newFolder();val id=legacy(root,partial=true)
        val store=RecordingRepository(root)
        assertEquals(SavedRecordingKind.LEGACY_TEXT,store.record(id)?.kind)
        assertEquals("historical words",store.record(id)?.transcript);assertTrue(checkNotNull(store.record(id)).partial)
        assertFalse(store.audio(id).exists());assertTrue(store.pending().isEmpty());assertTrue(store.ownedIds().isEmpty())
        store.saveText(RecordingTextSlot.MAIN,"edited historical words",id)
        val reopened=RecordingRepository(root)
        assertEquals("edited historical words",reopened.savedDraft(RecordingTextSlot.MAIN).text)
        assertEquals(id,reopened.savedDraft(RecordingTextSlot.MAIN).sourceId)
        assertFalse(reopened.audio(id).exists());assertTrue(reopened.pending().isEmpty())
        assertTrue(reopened.discard(id));assertEquals("",RecordingRepository(root).savedDraft(RecordingTextSlot.MAIN).text)
    }
    @Test fun textOnlyAndAudioRowsTogetherReachTheSameHundredItemLimit() {
        val root=temporary.newFolder();repeat(99){legacy(root,"legacy $it")}
        val store=RecordingRepository(root);val audio=store.allocate().apply {writeText("kept audio")}
        store.finalized(audio);store.retain(audio,SavedRecordingFailure.NETWORK)
        assertEquals(100,store.capacity().used);assertTrue(store.capacity().full)
        assertThrows(HistoryCapacityReached::class.java){store.allocate()}
        assertTrue(store.complete(checkNotNull(store.beginUpload(audio)),"explicit retry result"))
        assertEquals("kept audio",audio.readText());assertEquals(100,RecordingRepository(root).capacity().used)
    }
    @Test fun loweringLimitPreservesLegacyTextAndAlreadyAllocatedAudio() {
        val root=temporary.newFolder();val id=legacy(root)
        val store=RecordingRepository(root);store.setHistoryLimit(2)
        val audio=store.allocate().apply {writeText("active audio")};store.setHistoryLimit(1)
        store.finalized(audio);store.retain(audio)
        assertEquals(2,store.capacity().used);assertEquals("historical words",store.record(id)?.transcript)
        assertEquals("active audio",audio.readText());assertTrue(store.capacity().full)
    }
    @Test fun restartKeepsInterruptedTextAndStagedMetadataFailuresCountedWithoutEphemeralAdmissions() {
        val root=temporary.newFolder();val id=legacy(root,partial=true,status="INTERRUPTED")
        val failed=File(root,UUID.randomUUID().toString()).apply {mkdirs()}
        File(failed,"record.properties.tmp").writeText("retained failed checkpoint bytes")
        val restarted=RecordingRepository(root)
        assertEquals(2,restarted.capacity().used);assertTrue(restarted.ownedIds().isEmpty())
        assertEquals("historical words",restarted.record(id)?.transcript)
        assertEquals(SavedRecordingStatus.UNAVAILABLE,restarted.record(failed.name)?.status)
        assertTrue(File(failed,"record.properties.tmp").exists())
    }
    @Test fun oldTextAboveTheLimitRemainsIntactUntilExplicitDeletion() {
        val root=temporary.newFolder();val ids=(0 until 3).map {legacy(root,"text $it")}
        val store=RecordingRepository(root);store.setHistoryLimit(1)
        assertEquals(3,store.capacity().used);assertTrue(store.pending().isEmpty())
        assertTrue(store.discard(ids.first()));assertEquals(2,RecordingRepository(root).capacity().used)
        assertEquals("text 1",store.record(ids[1])?.transcript)
    }
}
