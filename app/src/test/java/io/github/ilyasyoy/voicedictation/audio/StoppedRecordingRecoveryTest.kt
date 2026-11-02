package io.github.ilyasyoy.voicedictation.audio

import java.io.File
import io.github.ilyasyoy.voicedictation.data.*
import org.junit.Assert.*
import org.junit.Test

class StoppedRecordingRecoveryTest {
    @Test fun timeoutKeepsCapacityReservedAndHistoryCannotDeleteUntilWriterClosed() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val repository = RecordingRepository(root)
            val file = repository.allocate().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val completion = StoppedRecordingRecovery { repository.retain(file, SavedRecordingFailure.STORAGE) }
            val id = checkNotNull(repository.record(file)).id
            completion.request() // stop timed out; lifecycle Cancel no longer owns this worker.
            assertFalse(repository.discard(id))
            assertEquals(SavedRecordingStatus.CAPTURING, checkNotNull(repository.record(file)).status)
            completion.afterRelease()
            assertEquals(SavedRecordingStatus.FAILED, checkNotNull(repository.record(file)).status)
            assertTrue(file.exists())
            assertTrue(repository.discard(id))
        } finally { root.deleteRecursively() }
    }
    @Test fun completionBeforeStopFailureTransfersExactlyOnce() {
        var retained = 0
        val completion = StoppedRecordingRecovery { retained++ }
        completion.afterRelease(); assertEquals(0, retained)
        completion.request(); completion.request(); completion.afterRelease(); assertEquals(1, retained)
    }
}
