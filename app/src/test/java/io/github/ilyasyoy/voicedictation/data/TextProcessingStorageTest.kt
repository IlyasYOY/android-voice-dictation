package io.github.ilyasyoy.voicedictation.data

import io.github.ilyasyoy.voicedictation.session.processSavedText
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TextProcessingStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "recordings")
    private fun complete(repository: RecordingRepository): String {
        val audio = repository.allocate().apply { writeText("synthetic audio") }; repository.finalized(audio)
        repository.complete(checkNotNull(repository.beginUpload(audio)), "Original transcript")
        return checkNotNull(repository.record(audio)).id
    }
    @Test fun additiveMigrationKeepsOldRowsDraftsAudioAndDoesNotStartWork() {
        val repository = RecordingRepository(root); val id = complete(repository)
        repository.saveText(RecordingTextSlot.MAIN, "Existing editable draft", id)
        val file = File(root, "$id/record.properties")
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        properties.keys.toList().filter { it.toString().startsWith("processing") || it == "processedText" }.forEach(properties::remove)
        file.outputStream().use { properties.store(it, null) }
        val restored = RecordingRepository(root)
        assertEquals("Original transcript", restored.record(id)!!.transcript)
        assertEquals(TextProcessingStatus.NONE, restored.record(id)!!.processingStatus)
        assertEquals("Existing editable draft", restored.savedText(RecordingTextSlot.MAIN))
        assertTrue(restored.audio(id).exists()); assertTrue(restored.pending().isEmpty())
    }
    @Test fun restartSettlesProcessingAndLateOrDuplicateTokensCannotCommit() {
        val repository = RecordingRepository(root); val id = complete(repository)
        val settings = TextProcessingSettings(true)
        val token = checkNotNull(repository.beginProcessing(id, settings))
        assertNull(repository.beginProcessing(id, settings)); assertFalse(repository.discard(id))
        val restored = RecordingRepository(root)
        assertEquals(TextProcessingStatus.INTERRUPTED, restored.record(id)!!.processingStatus)
        assertFalse(restored.completeProcessing(token, "Late"))
        repository.endProcessing(token, true)
        assertFalse(repository.completeProcessing(token, "Late"))
        val next = checkNotNull(repository.beginProcessing(id, settings))
        assertTrue(repository.completeProcessing(next, "First result"))
        assertFalse(repository.completeProcessing(next, "Duplicate"))
        assertEquals("Original transcript", repository.record(id)!!.transcript)
        assertEquals("First result", RecordingRepository(root).record(id)!!.processedText)
    }
    @Test fun textOnlyRepeatsAlwaysUseOriginalKeepOldResultOnFailureAndNeverMutateGlobalSettings() = runTest {
        val repository = RecordingRepository(root); val id = complete(repository)
        val global = TextProcessingSettings(true, prompt = "Global prompt")
        var calls = 0
        val processor = TextProcessor { text, key, settings ->
            calls++; assertEquals("Original transcript", text); assertEquals("synthetic", key)
            if (calls == 2) throw IOException()
            "Edited with ${settings.prompt}"
        }
        val changed = global.copy(prompt = "This attempt only")
        assertEquals("Edited with This attempt only", processSavedText(repository, id, changed, { "synthetic" }, processor))
        try { processSavedText(repository, id, changed.copy(prompt = "Failure"), { "synthetic" }, processor); fail() } catch (_: IOException) { }
        assertEquals("Edited with This attempt only", repository.record(id)!!.processedText)
        assertEquals("Original transcript", repository.record(id)!!.transcript)
        assertEquals("Global prompt", global.prompt); assertEquals(2, calls)
        assertTrue(repository.pending().isEmpty()); assertTrue(repository.audio(id).exists())
    }
    @Test fun repeatedTapAndCancellationAreClaimedBeforeSuspension() = runTest {
        val repository = RecordingRepository(root); val id = complete(repository)
        val response = CompletableDeferred<String>(); var calls = 0
        val processor = TextProcessor { _, _, _ -> calls++; response.await() }
        val task = launch { processSavedText(repository, id, TextProcessingSettings(true), { "synthetic" }, processor) }
        runCurrent()
        assertNull(processSavedText(repository, id, TextProcessingSettings(true), { "synthetic" }, processor))
        assertEquals(1, calls); task.cancelAndJoin()
        assertEquals(TextProcessingStatus.INTERRUPTED, repository.record(id)!!.processingStatus)
        assertTrue(repository.ownedIds().isEmpty()); assertTrue(repository.discard(id))
    }
    @Test fun failedSourceCommitNeverPermitsSecondNetworkRequest() = runTest {
        var fail = false
        val repository = RecordingRepository(root, commit = RecordingCommit { file, values ->
            if (fail) throw IOException("synthetic disk full")
            AtomicRecordingCommit.write(file, values)
        })
        val id = complete(repository); fail = true; var calls = 0
        try { processSavedText(repository, id, TextProcessingSettings(true), { "synthetic" }, TextProcessor { _, _, _ -> calls++; "No" }); fail() }
        catch (_: IOException) { }
        assertEquals(0, calls); assertEquals("Original transcript", RecordingRepository(root).record(id)!!.transcript)
    }
}
