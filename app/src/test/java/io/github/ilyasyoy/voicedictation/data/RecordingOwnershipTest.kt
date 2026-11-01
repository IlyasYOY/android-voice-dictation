package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecordingOwnershipTest {
    @Test fun `new owner never deletes another owners active or retry file`() {
        val directory=Files.createTempDirectory("recording-owners").toFile()
        try {
            val store=RecordingRepository(directory)
            val first=store.allocate().apply {writeText("pending retry")};store.finalized(first)
            store.allocate().writeText("other capture")
            assertEquals("pending retry",first.readText())
        } finally {directory.deleteRecursively()}
    }
    @Test fun `live-only startup imports old cache instead of sweeping its audio`() {
        val directory=Files.createTempDirectory("live-only-startup").toFile()
        try {
            val cache=File(directory,"cache").apply {mkdir()}
            val orphan=File(cache,"crash-orphan.m4a").apply {writeText("synthetic")}
            val store=RecordingRepository(File(directory,"durable"));store.migrateLegacy(cache)
            var allocations=0
            val deferred=DeferredAudioRecorder {allocations++;error("no file recording in live-only launch")}
            deferred.cancel();assertEquals(0,allocations)
            assertFalse(orphan.exists());assertEquals("synthetic",store.audio(store.records().single().id).readText())
        } finally {directory.deleteRecursively()}
    }
    @Test fun `live-only cancel never instantiates file recorder`() {
        var allocations=0
        val recorder=DeferredAudioRecorder {allocations++;error("file recorder must stay unallocated")}
        repeat(3) {recorder.cancel()};assertEquals(0,allocations)
    }
}
