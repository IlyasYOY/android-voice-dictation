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
            assertSame(first, second); assertEquals("pending retry", retained.readText())
            second.create(); assertTrue(retained.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `live-only cancel never instantiates file recorder`() {
        var allocations = 0
        val recorder = DeferredAudioRecorder { allocations++; error("file recorder must stay unallocated") }
        repeat(3) { recorder.cancel() }; assertEquals(0, allocations)
    }
}
