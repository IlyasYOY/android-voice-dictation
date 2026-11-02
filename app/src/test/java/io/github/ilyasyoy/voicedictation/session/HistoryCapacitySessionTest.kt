package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCapacitySessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Recorder(private val store: RecordingRepository) : AudioRecorder {
        var starts = 0
        var file: File? = null
        override fun start(onLimit: () -> Unit) { file = store.allocate(); starts++; checkNotNull(file).writeText("immutable original audio") }
        override fun stop(): File = checkNotNull(file).also { store.finalized(it); file = null }
        override fun cancel() { file?.let { store.discardOwned(it) }; file = null }
    }
    @Test fun fullHistoryBlocksMicrophoneWithoutNetworkErrorOrMutation() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        val kept = store.allocate().apply { writeText("kept failed audio") }; store.finalized(kept); store.retain(kept, SavedRecordingFailure.NETWORK)
        val recorder = Recorder(store); var uploads = 0
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> uploads++; "unused" }, recordings = StoredRecordingRetention(store))
        session.start(true); runCurrent()
        assertTrue(session.state.value.capacityBlocked); assertNull(session.state.value.problem)
        assertEquals(0, recorder.starts); assertEquals(0, uploads); assertEquals("kept failed audio", kept.readText())
        session.close(); assertEquals(1, store.records().size)
    }
    @Test fun failedCurrentRecordingCanRetryAtCapacityAndKeepSuccessAudioAndText() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        val recorder = Recorder(store); var uploads = 0
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ ->
            if (++uploads == 1) throw IOException("synthetic offline") else "successful retry words"
        }, recordings = StoredRecordingRetention(store))
        session.setKey("synthetic"); session.start(true); session.stop(); runCurrent()
        assertTrue(store.capacity().full); assertTrue(session.state.value.canRetry)
        session.retry(); session.retry(); runCurrent()
        assertEquals(2, uploads); assertEquals("successful retry words", store.records().single().transcript)
        assertTrue(store.audio(store.records().single().id).exists()); session.close()
    }
    @Test fun admittedRecordCanFinishAfterLimitReductionWithoutEviction() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(2)
        val kept = store.allocate().apply { writeText("older") }; store.finalized(kept); store.retain(kept)
        val session = DictationSession(backgroundScope, Recorder(store), Transcriber { _, _ -> "new words" }, recordings = StoredRecordingRetention(store))
        session.setKey("synthetic"); session.start(true); store.setHistoryLimit(1)
        session.stop(); runCurrent()
        assertEquals(2, store.capacity().used); assertEquals("older", kept.readText())
        assertTrue(store.records().any { it.transcript == "new words" }); session.close()
    }
    @Test fun floatingUsesSameCapacityGateWithoutChangingEditor() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        store.allocate().also { it.writeText("kept"); store.finalized(it); store.retain(it) }
        val recorder = Recorder(store)
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> error("no HTTP") }, recordings = StoredRecordingRetention(store))
        val editor = EditorToken("fixture.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
        val environment = FloatingEnvironment(editor, true, true, true, true, true)
        var blocked = false
        val capture = FloatingCapture(backgroundScope, session, editor, { environment }, { fail("editor valid") }, { blocked = it.capacityBlocked })
        capture.start(); runCurrent()
        assertTrue(blocked); assertEquals(0, recorder.starts); assertEquals(editor, environment.editor)
        capture.close(); assertEquals(1, store.records().size)
    }
}
