package io.github.ilyasyoy.voicedictation

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android private filesystem, atomic rename/directory sync; no key, audio capture or network. */
class DurableFileSystemTest {
    @Test fun atomicStoppedAudioResponseDraftAndDeletionSurviveRepositoryReopen() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"filesystem-test-${UUID.randomUUID()}")
        try {
            val store=RecordingRepository(root)
            val audio=store.allocate().apply {writeText("SYNTHETIC FIRST|LAST")}
            store.finalized(audio,3);store.retain(audio)
            val restored=RecordingRepository(root)
            assertEquals(1,restored.pending().size)
            val id=checkNotNull(restored.record(audio)).id
            assertTrue(restored.complete(checkNotNull(restored.beginUpload(audio)),"Durable words"))
            restored.saveText(RecordingTextSlot.MAIN,"Durable words",id)
            val next=RecordingRepository(root)
            assertEquals("Durable words",next.savedDraft(RecordingTextSlot.MAIN).text)
            assertEquals(id,next.savedDraft(RecordingTextSlot.MAIN).sourceId)
            assertEquals("SYNTHETIC FIRST|LAST",audio.readText())
            assertTrue(next.discard(id));assertFalse(audio.exists())
            assertTrue(RecordingRepository(root).records().isEmpty())
            assertEquals("",RecordingRepository(root).savedText(RecordingTextSlot.MAIN))
        } finally {root.deleteRecursively()}
    }
}
