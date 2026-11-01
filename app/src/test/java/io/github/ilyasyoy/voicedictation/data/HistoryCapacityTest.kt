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
    @Test fun hundredthPlaceStopsBothNewKindsAndKeepsEveryFailedPendingSuccessfulItem() {
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
        assertThrows(HistoryCapacityReached::class.java) { store.reserveLive() }
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
            val attempts = listOf(false, true).map { live -> pool.submit<Boolean> {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                try { if (live) store.reserveLive() else store.allocate(); true }
                catch (_: HistoryCapacityReached) { false }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, store.capacity().used); assertEquals(1, store.ownedIds().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun lowerLimitDoesNotInvalidateAlreadyAdmittedRecordOrLiveCheckpoint() {
        val root = temporary.newFolder(); val store = RecordingRepository(root)
        store.setHistoryLimit(2)
        val record = store.allocate().apply { writeText("record bytes") }
        val live = store.reserveLive()
        store.setHistoryLimit(1)
        store.finalized(record); store.retain(record)
        assertEquals(live, store.liveText(live, "received live words", true, 2, active = true))
        assertFalse(store.discard(live)) // Active Live is still owned, even above limit.
        store.releaseText(live)
        assertEquals(2, store.capacity().used)
        assertEquals("received live words", store.record(live)?.transcript)
        assertEquals("record bytes", record.readText())
        assertThrows(HistoryCapacityReached::class.java) { store.allocate() }
        assertEquals(2, RecordingRepository(root).records().size)
    }
    @Test fun emptyLiveAdmissionIsReleasedWithoutDeletingAnySavedItem() {
        val root = temporary.newFolder(); val store = RecordingRepository(root); store.setHistoryLimit(1)
        val id = store.reserveLive()
        assertEquals(0, store.records().size); assertEquals(1, store.capacity().used)
        store.releaseText(id)
        assertEquals(0, store.capacity().used); assertTrue(store.records().isEmpty())
    }
    @Test fun processDeathReclaimsEmptyLiveAdmissionButKeepsInterruptedRecordAndLiveText() {
        val root = temporary.newFolder(); val store = RecordingRepository(root); store.setHistoryLimit(3)
        val record = store.allocate().apply { writeText("interrupted bytes") }
        val live = store.reserveLive(); store.liveText(live, "checkpointed words", true, 4, active = true)
        store.reserveLive() // No text checkpoint, so no saved item exists for this admission.
        assertEquals(3, store.capacity().used)
        val restarted = RecordingRepository(root)
        assertEquals(2, restarted.capacity().used); assertEquals(0, restarted.capacity().reservedLive)
        assertEquals(SavedRecordingStatus.INTERRUPTED, restarted.record(record)?.status)
        assertEquals("checkpointed words", restarted.record(live)?.transcript)
        assertEquals("interrupted bytes", record.readText())
    }
    @Test fun oldHistoryAndSafetyPreservationMayExceedLimitWithoutEviction() {
        val root = temporary.newFolder(); val cache = temporary.newFolder()
        repeat(103) { File(cache, "$it.m4a").writeText("legacy-$it") }
        val store = RecordingRepository(root); store.migrateLegacy(cache)
        assertEquals(103, store.records().size); assertTrue(store.capacity().full)
        val existing = temporary.newFile("already-stopped.m4a").apply { writeText("must keep") }
        val retained = store.retain(existing)
        assertEquals("must keep", retained.readText()); assertEquals(104, store.records().size)
        store.liveText(null, "already received emergency text", true, 3)
        assertEquals(105, store.records().size)
        assertThrows(HistoryCapacityReached::class.java) { store.reserveLive() }
        assertEquals(105, RecordingRepository(root).records().size)
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
    @Test fun failedRecordAllocationIsUnownedAndCountedInsteadOfBeingBypassed() {
        val store = RecordingRepository(temporary.newFolder(), commit = RecordingCommit { _, _ -> throw IOException("fixture") })
        assertThrows(IOException::class.java) { store.allocate() }
        assertEquals(1, store.capacity().used); assertTrue(store.ownedIds().isEmpty())
        assertEquals(SavedRecordingStatus.UNAVAILABLE, store.records().single().status)
    }
    @Test fun firstLiveStagingFailureRemainsCountedAfterAdmissionEndsAndRestart() {
        val root = temporary.newFolder()
        val store = RecordingRepository(root, commit = RecordingCommit { target, p ->
            if (target.name == "record.properties") {
                java.io.FileOutputStream(File(target.parentFile, "record.properties.tmp")).use { p.store(it, null); it.fd.sync() }
                throw IOException("before rename fixture")
            }
            AtomicRecordingCommit.write(target, p)
        }); store.setHistoryLimit(1)
        val id = store.reserveLive()
        assertThrows(IOException::class.java) { store.liveText(id, "already received", true, 1, true) }
        store.releaseText(id)
        assertEquals(1, store.capacity().used); assertTrue(store.capacity().full)
        assertEquals(SavedRecordingKind.LIVE, store.record(id)?.kind)
        assertThrows(HistoryCapacityReached::class.java) { store.allocate() }
        val staged = File(root, "$id/record.properties.tmp"); val bytes = staged.readBytes()
        val restarted = RecordingRepository(root)
        assertEquals(1, restarted.capacity().used)
        assertEquals(SavedRecordingStatus.UNAVAILABLE, restarted.record(id)?.status)
        assertArrayEquals(bytes, staged.readBytes())
    }
    @Test fun firstLivePostRenameFailureKeepsCommittedTextAndCountOnRestart() {
        val root = temporary.newFolder()
        val store = RecordingRepository(root, commit = RecordingCommit { target, p ->
            AtomicRecordingCommit.write(target, p)
            if (target.name == "record.properties") throw IOException("after rename fixture")
        }); store.setHistoryLimit(1)
        val id = store.reserveLive()
        assertThrows(IOException::class.java) { store.liveText(id, "committed Live text", true, 1, true) }
        store.releaseText(id)
        assertEquals(1, store.capacity().used)
        val restarted = RecordingRepository(root)
        assertEquals(1, restarted.capacity().used)
        assertEquals("committed Live text", restarted.record(id)?.transcript)
    }
    @Test fun LiveCheckpointRetriesItsAdmittedDirectoryAfterAnInterruptedCommit() {
        var fail = true
        val store = RecordingRepository(temporary.newFolder(), commit = RecordingCommit { target, values ->
            if (fail && target.name == "record.properties") { fail = false; throw IOException("fixture") }
            AtomicRecordingCommit.write(target, values)
        })
        val id = store.reserveLive()
        assertThrows(IOException::class.java) { store.liveText(id, "first received", true, 1, true) }
        assertEquals(1, store.capacity().used)
        assertEquals(id, store.liveText(id, "complete received words", false, 2, true))
        assertEquals(1, store.capacity().used); assertEquals("complete received words", store.record(id)?.transcript)
        assertNull(store.record(id)?.failure)
    }
}
