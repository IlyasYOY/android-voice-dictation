package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TextProcessingSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository() = RecordingRepository(File(temporary.root, "recordings"))
    private class Capture(val repository: RecordingRepository) : AudioRecorder {
        lateinit var audio: File
        override fun start(onLimit: () -> Unit) { audio = repository.allocate().apply { writeText("synthetic audio") } }
        override fun stop() = audio.also { repository.finalized(it, 2) }
        override fun cancel() = Unit
    }
    @Test fun rawIsDurableBeforeSecondRequestAndOnlyProcessedResultIsInsertionEligible() = runTest {
        val repository = repository(); val recorder = Capture(repository)
        val response = CompletableDeferred<String>(); var uploads = 0; var processes = 0
        val results = mutableListOf<String>()
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> uploads++; "Original words" },
            recordings = StoredRecordingRetention(repository, true), processingSettings = { TextProcessingSettings(true) },
            textProcessor = TextProcessor { original, key, _ ->
                processes++; assertEquals("Original words", original); assertEquals("synthetic", key)
                assertEquals("Original words", repository().records().single().transcript)
                assertTrue(repository().pending().isEmpty()); response.await()
            })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.state.collect { if (it.phase == Phase.RESULT) results += it.text }
        }
        session.setKey("synthetic"); session.start(true); session.stop(); runCurrent()
        assertEquals(Phase.PROCESSING, session.state.value.phase); assertTrue(session.state.value.audioBusy)
        assertTrue(results.isEmpty()); assertEquals(1, uploads); assertEquals(1, processes)
        repeat(3) { session.stop(); session.retry() }; runCurrent()
        response.complete("Edited words"); runCurrent()
        assertEquals(listOf("Edited words"), results)
        val saved = repository().records().single()
        assertEquals("Original words", saved.transcript); assertEquals("Edited words", saved.processedText)
        assertEquals("Edited words", repository().savedText(RecordingTextSlot.MAIN))
        assertEquals(1, uploads); assertEquals(1, processes); assertTrue(recorder.audio.exists()); session.close()
    }
    @Test fun secondRequestFailureNeverBecomesAudioRetryOrPublishesIntermediateResult() = runTest {
        val repository = repository(); val recorder = Capture(repository); var calls = 0
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> "Keep the original" },
            recordings = StoredRecordingRetention(repository), processingSettings = { TextProcessingSettings(true) },
            textProcessor = TextProcessor { _, _, _ -> calls++; throw IOException("synthetic failure") })
        session.setKey("synthetic"); session.start(true); session.stop(); runCurrent()
        assertEquals(Phase.ERROR, session.state.value.phase); assertTrue(session.state.value.processingFailed)
        assertEquals("Keep the original", session.state.value.text); assertFalse(session.state.value.canRetry)
        repeat(3) { session.retry(); runCurrent() }; assertEquals(1, calls)
        assertEquals(SavedRecordingStatus.COMPLETE, repository().records().single().status)
        assertEquals(TextProcessingStatus.FAILED, repository().records().single().processingStatus)
        assertTrue(repository().pending().isEmpty()); session.close()
    }
    @Test fun cancellationCloseAndNewRecordingInvalidateNonCooperativeLateResult() = runTest {
        for (close in listOf(false, true)) {
            val root = File(temporary.root, "records-$close"); val repository = RecordingRepository(root); val recorder = Capture(repository)
            val response = CompletableDeferred<String>()
            val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> "Original" },
                recordings = StoredRecordingRetention(repository), processingSettings = { TextProcessingSettings(true) },
                textProcessor = TextProcessor { _, _, _ -> withContext(NonCancellable) { response.await() } })
            session.setKey("synthetic"); session.start(true); session.stop(); runCurrent()
            val originalId = repository.records().single().id
            if (close) session.close() else session.cancel()
            assertEquals(TextProcessingStatus.INTERRUPTED, repository.records().single().processingStatus)
            if (!close) session.start(true)
            response.complete("Too late"); runCurrent()
            assertEquals("", repository.record(originalId)!!.processedText)
            assertEquals("Original", repository.record(originalId)!!.transcript)
            if (!close) { assertEquals(Phase.RECORDING, session.state.value.phase); session.close() }
        }
    }
    @Test fun disabledMeansNoSecondCallAndOptionsAreSnapshottedForTheAttempt() = runTest {
        val repository = repository(); val recorder = Capture(repository); var calls = 0
        var options = TextProcessingSettings()
        val raw = CompletableDeferred<String>()
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> raw.await() },
            recordings = StoredRecordingRetention(repository), processingSettings = { options },
            textProcessor = TextProcessor { _, _, _ -> calls++; "Unexpected" })
        session.setKey("synthetic"); session.start(true); session.stop(); runCurrent()
        options = options.copy(enabled = true); raw.complete("Unchanged"); runCurrent()
        assertEquals(Phase.RESULT, session.state.value.phase); assertEquals("Unchanged", session.state.value.text)
        assertEquals(0, calls); session.close()
    }
    @Test fun floatingLeaseCoversTranscriptionAndTextProcessingWithoutGapAndReleasesOnFailure() = runTest {
        val repository = repository(); val recorder = Capture(repository); val response = CompletableDeferred<String>()
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> "Original" },
            recordings = StoredRecordingRetention(repository), processingSettings = { TextProcessingSettings(true) },
            textProcessor = TextProcessor { _, _, _ -> response.await() })
        session.setKey("synthetic")
        val editor = EditorToken("test.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
        val lease = mutableListOf<Boolean>()
        val capture = FloatingCapture(backgroundScope, session, editor,
            { FloatingEnvironment(editor, true, true, true, true, true) }, {}, {}, { lease += it })
        capture.start(); runCurrent(); session.stop(); runCurrent()
        assertEquals(Phase.PROCESSING, session.state.value.phase); assertEquals(listOf(true), lease)
        response.completeExceptionally(IOException()); runCurrent()
        assertEquals(listOf(true, false), lease); capture.close()
    }
}
