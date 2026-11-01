package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
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
        override fun start(onLimit: () -> Unit) {
            file = store.allocate(); starts++; checkNotNull(file).writeText("immutable original audio")
        }
        override fun stop(): File = checkNotNull(file).also { store.finalized(it); file = null }
        override fun cancel() { file?.let { store.discardOwned(it) }; file = null }
    }
    private fun fill(store: RecordingRepository) {
        val f = store.allocate().apply { writeText("kept failed audio") }
        store.finalized(f); store.retain(f, SavedRecordingFailure.NETWORK)
    }
    @Test fun fullRecordAndLiveDoNotStartAudioConnectOrUploadAndAreNotNetworkErrors() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1); fill(store)
        for (mode in Mode.entries) {
            val recorder = Recorder(store); var pcm = 0; var connects = 0; var uploads = 0
            val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> uploads++; "unused" },
                object : PcmSource {
                    override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) { pcm++ }
                    override fun stop() = Unit
                }, RealtimeTranscriber { _, _ -> connects++; error("must not connect") },
                recordings = StoredRecordingRetention(store))
            session.setKey("synthetic"); session.setMode(mode); session.start(true); runCurrent()
            assertTrue(session.state.value.capacityBlocked); assertNull(session.state.value.problem)
            assertEquals(0, recorder.starts); assertEquals(0, pcm); assertEquals(0, connects); assertEquals(0, uploads)
            session.close(); assertEquals(1, store.records().size)
        }
    }
    @Test fun failedCurrentRecordingCanRetryAtCapacityAndKeepSuccessAudioAndText() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        val recorder = Recorder(store); var uploads = 0
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ ->
            if (++uploads == 1) throw IOException("synthetic offline") else "successful retry words"
        }, recordings = StoredRecordingRetention(store))
        session.setKey("synthetic"); session.start(true); session.stop(); runCurrent()
        assertTrue(store.capacity().full); assertTrue(session.state.value.canRetry)
        session.retry(); runCurrent()
        assertEquals(2, uploads); assertEquals(Phase.RESULT, session.state.value.phase)
        assertEquals("successful retry words", store.records().single().transcript)
        assertEquals("immutable original audio", store.audio(store.records().single().id).readText())
        session.close(); assertEquals(1, store.records().size)
    }
    @Test fun emptyLiveCancellationReleasesPlaceAndAdmittedLiveKeepsWordsAfterLimitReduction() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(2)
        lateinit var emit: (LiveEvent) -> Unit
        var starts = 0
        val session = DictationSession(backgroundScope, Recorder(store), Transcriber { _, _ -> error("no HTTP") },
            object : PcmSource {
                override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) { starts++ }
                override fun stop() = Unit
            }, RealtimeTranscriber { _, callback -> emit = callback; object : LiveSession {
                override fun append(pcm: ByteArray) = Unit
                override fun finish() = Unit
                override fun cancel() = Unit
            } }, recordings = StoredRecordingRetention(store))
        session.setKey("synthetic"); session.setMode(Mode.LIVE)
        session.start(true); assertEquals(1, store.capacity().used); session.cancel()
        assertEquals(0, store.capacity().used)
        session.start(true); emit(LiveEvent.Ready); runCurrent()
        fill(store); store.setHistoryLimit(1)
        emit(LiveEvent.Transcript("one Live session", false, 1)); runCurrent()
        session.stop(); emit(LiveEvent.Finished); runCurrent()
        assertEquals(Phase.RESULT, session.state.value.phase); assertEquals(2, store.capacity().used)
        assertTrue(store.records().any { it.kind == SavedRecordingKind.LIVE && it.transcript == "one Live session" })
        assertTrue(store.ownedIds().isEmpty()); assertEquals(1, starts)
        session.close()
    }
    @Test fun LiveAdmissionStaysOwnedUntilFinalCheckpointEvenWhenEarlierCommitsFailed() = runTest {
        var attempts = 0
        val store = RecordingRepository(temporary.newFolder(), commit = RecordingCommit { target, p ->
            if (target.name == "record.properties" && ++attempts <= 2) throw IOException("checkpoint fixture")
            AtomicRecordingCommit.write(target, p)
        }); store.setHistoryLimit(1)
        lateinit var emit: (LiveEvent) -> Unit
        val session = DictationSession(backgroundScope, Recorder(store), Transcriber { _, _ -> error("no HTTP") },
            object : PcmSource {
                override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) = Unit
                override fun stop() = Unit
            }, RealtimeTranscriber { _, callback -> emit = callback; object : LiveSession {
                override fun append(pcm: ByteArray) = Unit
                override fun finish() = Unit
                override fun cancel() = Unit
            } }, recordings = StoredRecordingRetention(store))
        session.setKey("synthetic"); session.setMode(Mode.LIVE); session.start(true)
        emit(LiveEvent.Ready); runCurrent()
        var freeDuringFinish = false
        val unsubscribe = store.observe { if (store.capacity().canStart) freeDuringFinish = true }
        emit(LiveEvent.Transcript("partial", true, 0)); runCurrent() // First failed checkpoint.
        session.stop() // Second failed checkpoint.
        emit(LiveEvent.Transcript("final words", false, 0)); emit(LiveEvent.Finished); runCurrent()
        unsubscribe()
        assertFalse(freeDuringFinish); assertEquals(1, store.capacity().used)
        assertEquals("final words", store.records().single().transcript)
        assertTrue(store.ownedIds().isEmpty()); session.close()
    }
    @Test fun floatingRaceUsesTheSameAdmissionGateAndPreservesItsEditorToken() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1); fill(store)
        val recorder = Recorder(store)
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> error("no HTTP") },
            recordings = StoredRecordingRetention(store))
        val editor = EditorToken("fixture.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
        val environment = FloatingEnvironment(editor, true, true, true, true, true)
        var blocked = false
        val capture = FloatingCapture(backgroundScope, session, editor, { environment }, { fail("editor valid") }, {},
            { blocked = it.capacityBlocked })
        capture.start(); runCurrent()
        assertTrue(blocked); assertEquals(0, recorder.starts); assertEquals(editor, environment.editor)
        capture.close(); assertEquals(1, store.records().size)
    }
    @Test fun staleFloatingLiveTargetDoesNotLeakAReservationAfterDelayedKeyLoad() = runTest {
        val store = RecordingRepository(temporary.newFolder()); store.setHistoryLimit(1)
        val key = CompletableDeferred<String?>(); var connects = 0
        val session = DictationSession(backgroundScope, Recorder(store), Transcriber { _, _ -> error("no HTTP") },
            realtime = RealtimeTranscriber { _, _ -> connects++; error("no connection") }, keyStore = object : ApiKeyStore {
                override suspend fun load() = key.await()
                override suspend fun save(key: String) = Unit
                override suspend fun forget() = Unit
            }, recordings = StoredRecordingRetention(store))
        val original = EditorToken("fixture.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
        var now = FloatingEnvironment(original, true, true, true, true, true)
        var invalid = 0
        val capture = FloatingCapture(backgroundScope, session, original, { now }, { invalid++ }, {}, {}, mode = Mode.LIVE)
        capture.start(); runCurrent(); now = now.copy(editor = original.copy(selectionStart = 8, selectionEnd = 8))
        key.complete("synthetic"); runCurrent()
        assertEquals(1, invalid); assertEquals(0, connects); assertEquals(0, store.capacity().used)
        capture.close()
    }
}
