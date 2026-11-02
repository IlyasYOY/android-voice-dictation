package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryCapacityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun pending(store: RecordingRepository, value: String = "original audio"): File = store.allocate().also {
        it.writeText(value); store.finalized(it, 3); store.retain(it)
    }
    @Test fun defaultAndExplicitLimitSurviveRestartWithoutChangingRows() {
        val root = temporary.newFolder(); val store = RecordingRepository(root)
        assertEquals(100, store.capacity().limit)
        val audio = pending(store)
        store.setHistoryLimit(1)
        val restarted = RecordingRepository(root)
        assertEquals(1, restarted.capacity().limit); assertTrue(restarted.capacity().full)
        assertEquals("original audio", audio.readText()); assertEquals(1, restarted.records().size)
        assertThrows(HistoryCapacityReached::class.java) { restarted.allocate() }
    }
    @Test fun inputValidationNeverClampsOrAcceptsZeroNegativeDecimalOrOverflow() {
        for (value in listOf("", " ", "0", "-1", "+1", "1.5", "1e2", "2147483648", "1 00"))
            assertNull(value, HistoryCapacity.parse(value))
        assertEquals(1, HistoryCapacity.parse(" 1 "))
        assertEquals(Int.MAX_VALUE, HistoryCapacity.parse(Int.MAX_VALUE.toString()))
        val store = RecordingRepository(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.setHistoryLimit(0) }
        assertEquals(100, store.capacity().limit)
    }
    @Test fun hundredthPlaceStopsNewCaptureAndKeepsEveryFailedPendingSuccessfulItem() {
        val store = RecordingRepository(temporary.newFolder())
        val files = (0 until 100).map { n -> pending(store, "audio-$n").also {
            when (n % 3) {
                0 -> store.complete(checkNotNull(store.beginUpload(it)), "text-$n")
                1 -> store.markFailed(it, SavedRecordingFailure.NETWORK)
            }
        } }
        val before = store.records()
        assertEquals(100, store.capacity().used); assertTrue(store.capacity().full)
        assertThrows(HistoryCapacityReached::class.java) { store.allocate() }
        assertEquals(before, store.records())
        files.forEachIndexed { n, file -> assertEquals("audio-$n", file.readText()) }
        val failed = files[1]
        assertTrue(store.complete(checkNotNull(store.beginUpload(failed)), "explicit retry result"))
        assertEquals(100, store.capacity().used)
        assertTrue(store.discard(checkNotNull(store.record(files[2])).id))
        assertEquals(99, store.capacity().used); assertTrue(store.capacity().canStart)
    }
    @Test fun simultaneousMainAndOverlayAdmissionsCannotTakeTheSameLastPlace() {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        val ready = CountDownLatch(2); val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf("main", "overlay").map { _ -> pool.submit<Boolean> {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                try { store.allocate(); true }
                catch (_: HistoryCapacityReached) { false }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, store.capacity().used); assertEquals(1, store.ownedIds().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun failedSettingCommitKeepsPreviousValueAndNeverDeletesHistory() {
        val root = temporary.newFolder(); var fail = false
        val store = RecordingRepository(root, commit = RecordingCommit { target, values ->
            if (fail && target.name == "history-limit.properties") throw IOException("fixture")
            AtomicRecordingCommit.write(target, values)
        })
        val file = pending(store); store.setHistoryLimit(2); fail = true
        assertThrows(IOException::class.java) { store.setHistoryLimit(1) }
        assertEquals(2, store.capacity().limit); assertEquals(2, RecordingRepository(root).capacity().limit)
        assertEquals("original audio", file.readText())
    }
    @Test fun unreadableSettingBlocksOnlyNewStartsAndCanBeRepairedExplicitly() {
        val root = temporary.newFolder(); val first = RecordingRepository(root); val file = pending(first)
        File(root, "history-limit.properties").writeText("format=999\nlimit=0\n")
        val store = RecordingRepository(root)
        assertFalse(store.capacity().readable)
        assertThrows(IOException::class.java) { store.requireCapacity() }
        assertTrue(store.complete(checkNotNull(store.beginUpload(file)), "existing retry still works"))
        store.setHistoryLimit(1); assertTrue(store.capacity().readable)
        assertTrue(store.discard(checkNotNull(store.record(file)).id))
    }
    @Test fun settingPostRenameFailureUsesVisibleLimitAndMatchesRestart() {
        val root = temporary.newFolder(); var fail = false
        val store = RecordingRepository(root, commit = RecordingCommit { target, values ->
            AtomicRecordingCommit.write(target, values)
            if (fail && target.name == "history-limit.properties") throw IOException("after rename fixture")
        })
        val file = pending(store); store.setHistoryLimit(2); fail = true
        assertThrows(IOException::class.java) { store.setHistoryLimit(1) }
        assertEquals(1, store.capacity().limit); assertEquals(1, RecordingRepository(root).capacity().limit)
        assertThrows(HistoryCapacityReached::class.java) { store.allocate() }
        assertEquals("original audio", file.readText())
    }
    @Test fun failedRecordAllocationIsUnownedAndCountedInsteadOfBeingBypassed() {
        val store = RecordingRepository(temporary.newFolder(), commit = RecordingCommit { _, _ -> throw IOException("fixture") })
        assertThrows(IOException::class.java) { store.allocate() }
        assertEquals(1, store.capacity().used); assertTrue(store.ownedIds().isEmpty())
        assertEquals(SavedRecordingStatus.UNAVAILABLE, store.records().single().status)
    }
}
