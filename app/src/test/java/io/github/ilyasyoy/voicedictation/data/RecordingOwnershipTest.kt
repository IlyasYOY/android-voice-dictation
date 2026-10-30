package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecordingOwnershipTest {
    @Test fun `new owner never deletes another owners active or retry file`() {
        val directory = Files.createTempDirectory("recording-owners").toFile()
        try {
            val orphan = File(directory, "previous-process.m4a").apply { writeText("orphan") }
            val first = RecordingFiles.shared(directory); assertFalse(orphan.exists())
            val retained = first.create().apply { writeText("pending retry") }
            val second = RecordingFiles.shared(directory)
            assertEquals("pending retry", retained.readText())
            second.create(); assertTrue(retained.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `process startup sweeps crash orphans even when owners never allocate file recorder`() {
        val directory = Files.createTempDirectory("live-only-startup").toFile()
        try {
            val orphan = File(directory, "crash-orphan.m4a").apply { writeText("synthetic") }
            val files = RecordingFiles.shared(directory) // Application/composition-root startup boundary.
            var allocations = 0
            val deferred = DeferredAudioRecorder { allocations++; error("no file recording in a live-only launch") }
            assertFalse(orphan.exists()); deferred.cancel(); assertEquals(0, allocations)
            val retry = files.create().apply { writeText("pending retry") }
            RecordingFiles.shared(directory)
            assertEquals("pending retry", retry.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `live-only cancel never instantiates file recorder`() {
        var allocations = 0
        val recorder = DeferredAudioRecorder { allocations++; error("file recorder must stay unallocated") }
        repeat(3) { recorder.cancel() }; assertEquals(0, allocations)
    }
}
