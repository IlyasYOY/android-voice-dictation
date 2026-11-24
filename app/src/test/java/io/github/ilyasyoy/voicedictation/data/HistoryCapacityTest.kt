package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.IOException
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
        assertTrue(restarted.capacity().canStart)
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
    @Test fun hundredAndFirstKeptRecordingReplacesOnlyTheOldestAndSurvivesRestart() {
        val root = temporary.newFolder(); val store = RecordingRepository(root, clock = { 1000L })
        val files = (0 until 100).map { n -> pending(store, "audio-$n").also {
            when (n % 3) {
                0 -> store.complete(checkNotNull(store.beginUpload(it)), "text-$n")
                1 -> store.markFailed(it, SavedRecordingFailure.NETWORK)
            }
        } }
        val olderId = checkNotNull(store.record(files.first())).id
        store.saveText(RecordingTextSlot.MAIN, "linked draft", olderId)
        val fresh = store.allocate().apply { writeText("new audio") }
        store.finalized(fresh)
        assertEquals("audio-0", files.first().readText())
        assertTrue(store.complete(checkNotNull(store.beginUpload(fresh)), "new transcript"))
        assertFalse(files.first().parentFile!!.exists())
        files.drop(1).forEachIndexed { n, file -> assertEquals("audio-${n + 1}", file.readText()) }
        val reopened = RecordingRepository(root)
        assertEquals(100, reopened.capacity().used)
        assertEquals(reopened.record(fresh)?.id, reopened.records().first().id)
        assertEquals("new transcript", reopened.records().first().transcript)
        assertEquals("new audio", fresh.readText())
        assertEquals("", reopened.savedDraft(RecordingTextSlot.MAIN).text)
    }

    @Test fun cancelledCaptureDoesNotEvictSavedHistory() {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        val kept = pending(store)
        val fresh = store.allocate().apply { writeText("cancelled audio") }
        store.finalized(fresh)
        assertTrue(store.discardOwned(fresh))
        assertEquals("original audio", kept.readText())
        assertEquals(1, store.capacity().used)
    }

    @Test fun activeOldestIsNotDeletedAndTrimResumesWhenItIsReleased() {
        val root = temporary.newFolder(); val store = RecordingRepository(root); store.setHistoryLimit(1)
        val older = pending(store)
        val token = checkNotNull(store.beginUpload(older))
        val fresh = pending(store, "kept after sign-off")
        assertEquals(2, store.capacity().used); assertTrue(older.exists())
        assertTrue(store.failed(token, SavedRecordingFailure.NETWORK))
        assertFalse(older.exists())
        assertEquals(checkNotNull(store.record(fresh)).id, store.records().single().id)
    }

    @Test fun successfulHistoryRetryReleasesDeferredCleanup() {
        val store = RecordingRepository(temporary.newFolder())
        store.setHistoryLimit(1)
        val oldest = pending(store, "A")
        val token = checkNotNull(store.beginUpload(oldest))
        val newest = pending(store, "B")
        assertEquals(2, store.records().size)
        assertTrue(store.complete(token, "recognized words"))
        assertFalse(oldest.exists())
        assertEquals("B", newest.readText())
        assertEquals(1, store.records().size)
    }

    @Test fun retryResultCanBeKeptUntilPostProcessingFinishes() {
        val store = RecordingRepository(temporary.newFolder())
        store.setHistoryLimit(1)
        val oldest = pending(store, "A")
        val id = checkNotNull(store.record(oldest)).id
        val token = checkNotNull(store.beginUpload(oldest))
        val newest = pending(store, "B")
        assertTrue(store.complete(token, "recognized words", protectResult = true))
        val processing = checkNotNull(store.beginProcessing(id, TextProcessingSettings()))
        store.resumeHistoryCleanup()
        assertTrue(oldest.exists())
        assertTrue(store.completeProcessing(processing, "refined words"))
        assertFalse(oldest.exists())
        assertEquals("B", newest.readText())
        assertEquals(1, store.records().size)
    }

    @Test fun deferredCleanupDoesNotApplyALaterDecreaseUntilAnotherRecordingIsKept() {
        val root = temporary.newFolder()
        val store = RecordingRepository(root)
        store.setHistoryLimit(2)
        val oldest = pending(store, "A")
        val next = pending(store, "B")
        assertNotNull(store.beginUpload(oldest))
        val newest = pending(store, "C")
        assertEquals(3, store.records().size)

        store.setHistoryLimit(1)
        val reopened = RecordingRepository(root)
        assertEquals(1, reopened.capacity().limit)
        assertFalse(oldest.exists())
        assertEquals("B", next.readText())
        assertEquals("C", newest.readText())
        assertEquals(2, reopened.records().size)

        val fresh = pending(reopened, "D")
        assertEquals("D", fresh.readText())
        assertFalse(next.exists())
        assertFalse(newest.exists())
        assertEquals(1, reopened.records().size)
    }

    @Test fun earlierCleanupCannotGrowAfterAnotherSaveAtAHigherLimit() {
        val root = temporary.newFolder()
        val store = RecordingRepository(root)
        store.setHistoryLimit(1)
        val oldest = pending(store, "A")
        assertNotNull(store.beginUpload(oldest))
        val second = pending(store, "B")
        store.setHistoryLimit(2)
        val third = pending(store, "C")
        assertEquals(3, store.records().size)
        store.setHistoryLimit(1)

        val reopened = RecordingRepository(root)
        assertFalse(oldest.exists())
        assertEquals("B", second.readText())
        assertEquals("C", third.readText())
        assertEquals(2, reopened.records().size)
    }

    @Test fun increasingTheLimitCancelsNowUnnecessaryDeferredCleanup() {
        val root = temporary.newFolder()
        val store = RecordingRepository(root)
        store.setHistoryLimit(1)
        val oldest = pending(store, "A")
        assertNotNull(store.beginUpload(oldest))
        val newest = pending(store, "B")
        store.setHistoryLimit(2)

        val reopened = RecordingRepository(root)
        assertEquals(2, reopened.records().size)
        assertEquals("A", oldest.readText())
        assertEquals("B", newest.readText())
        reopened.setHistoryLimit(1)
        assertEquals(2, RecordingRepository(root).records().size)
    }

    @Test fun failedSaveNeverEvictsAndDeferredDeleteResumesAfterRestart() {
        val root = temporary.newFolder()
        var failDelete = false
        var failComplete = false
        val store = RecordingRepository(root, commit = RecordingCommit { target, props ->
            if (failComplete && props.getProperty("status") == "COMPLETE") throw IOException("fixture")
            AtomicRecordingCommit.write(target, props)
        }, deletion = object : RecordingDeletion {
            override fun delete(file: File) {
                if (failDelete && file.name == "audio.m4a") throw IOException("fixture")
                super.delete(file)
            }
        })
        store.setHistoryLimit(1)
        val old = pending(store)
        val fresh = store.allocate().apply { writeText("new audio") }
        store.finalized(fresh)
        val token = checkNotNull(store.beginUpload(fresh))
        failComplete = true
        assertThrows(IOException::class.java) { store.complete(token, "new transcript") }
        assertEquals("original audio", old.readText())
        failComplete = false; failDelete = true
        assertTrue(store.complete(token, "new transcript"))
        assertTrue(old.exists())
        val reopened = RecordingRepository(root)
        assertFalse(old.exists())
        assertEquals("new transcript", reopened.records().single().transcript)
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
        assertTrue(store.capacity().canStart)
        assertEquals("original audio", file.readText())
    }
    @Test fun failedRecordAllocationIsUnownedAndCountedInsteadOfBeingBypassed() {
        val store = RecordingRepository(temporary.newFolder(), commit = RecordingCommit { _, _ -> throw IOException("fixture") })
        assertThrows(IOException::class.java) { store.allocate() }
        assertEquals(1, store.capacity().used); assertTrue(store.ownedIds().isEmpty())
        assertEquals(SavedRecordingStatus.UNAVAILABLE, store.records().single().status)
    }
    @Test fun oldAudioAboveLimitAndSafetyRecoveryAreKeptWithoutEviction() {
        val root=temporary.newFolder();val cache=temporary.newFolder()
        repeat(103){File(cache,"$it.m4a").writeText("legacy-$it")}
        val store=RecordingRepository(root);store.migrateLegacy(cache)
        assertEquals(103,store.records().size);assertTrue(store.capacity().full)
        val existing=temporary.newFile("already-stopped.m4a").apply {writeText("must keep")}
        val retained=store.retain(existing)
        assertEquals("must keep",retained.readText());assertEquals(104,store.capacity().used)
        assertTrue(store.capacity().canStart)
        assertEquals(104,RecordingRepository(root).capacity().used)
    }

}
