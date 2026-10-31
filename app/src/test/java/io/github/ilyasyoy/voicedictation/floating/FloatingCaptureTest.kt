package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.DictationSession
import io.github.ilyasyoy.voicedictation.session.Mode
import io.github.ilyasyoy.voicedictation.session.Phase
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingCaptureTest {
    private fun editor() = EditorToken("test.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
    @Test fun `every fresh safety gate prevents key completion from starting audio`() = runTest {
        val origin = editor()
        val valid = FloatingEnvironment(origin, true, true, true, true, true)
        val changes = listOf(valid.copy(editor = origin.copy(viewId = "other")), valid.copy(editor = origin.copy(selectionStart = 8, selectionEnd = 8)),
            valid.copy(enabled = false), valid.copy(awake = false), valid.copy(keyboard = false), valid.copy(microphone = false),
            valid.copy(notifications = false), valid.copy(editor = null))
        for (changed in changes) {
            val key = CompletableDeferred<String?>(); var now = valid; var connects = 0; var invalid = 0
            val store = object : ApiKeyStore {
                override suspend fun load() = key.await()
                override suspend fun save(key: String) = Unit
                override suspend fun forget() = Unit
            }
            val session = DictationSession(backgroundScope, recorder(), Transcriber { _, _ -> "unused" },
                realtime = RealtimeTranscriber { _, _ -> connects++; error("must not connect") }, keyStore = store)
            lateinit var capture: FloatingCapture
            capture = FloatingCapture(backgroundScope, session, origin, { now }, { invalid++; capture.close() }, {}, {}, mode=Mode.LIVE)
            capture.start(); runCurrent(); now = changed; key.complete("synthetic-key"); runCurrent()
            assertEquals(changed.toString(), 0, connects); assertEquals(1, invalid)
        }
    }
    @Test fun `Cancel disable and unbind close ownership before delayed key completion`() = runTest {
        repeat(3) {
            val key = CompletableDeferred<String?>(); var connects = 0; var states = 0
            val store = object : ApiKeyStore {
                override suspend fun load() = key.await()
                override suspend fun save(key: String) = Unit
                override suspend fun forget() = Unit
            }
            val session = DictationSession(backgroundScope, recorder(), Transcriber { _, _ -> "unused" },
                realtime = RealtimeTranscriber { _, _ -> connects++; error("must not connect") }, keyStore = store)
            val capture = FloatingCapture(backgroundScope, session, editor(),
                { FloatingEnvironment(editor(), true, true, true, true, true) }, {}, {}, { states++ }, mode=Mode.LIVE)
            capture.start(); runCurrent(); val observed = states; capture.close(); capture.close()
            key.complete("synthetic-key"); runCurrent()
            assertEquals(0, connects); assertEquals(observed, states); assertEquals("", session.state.value.key)
        }
    }
    @Test fun `unchanged editor starts exactly one session after key load`() = runTest {
        val key = CompletableDeferred<String?>(); var connects = 0
        val store = object : ApiKeyStore {
            override suspend fun load() = key.await()
            override suspend fun save(key: String) = Unit
            override suspend fun forget() = Unit
        }
        val session = DictationSession(backgroundScope, recorder(), Transcriber { _, _ -> "unused" },
            object : PcmSource { override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) = Unit; override fun stop() = Unit },
            RealtimeTranscriber { _, _ -> connects++; object : LiveSession {
                override fun append(pcm: ByteArray) = Unit; override fun finish() = Unit; override fun cancel() = Unit
            } }, store)
        val capture = FloatingCapture(backgroundScope, session, editor(),
            { FloatingEnvironment(editor(), true, true, true, true, true) }, { fail("eligible") }, { fail("saved key") }, {}, mode=Mode.LIVE)
        capture.start(); runCurrent(); assertEquals(0, connects)
        key.complete("synthetic-key"); runCurrent(); assertEquals(1, connects); capture.close()
    }
    @Test fun `terminal callback retains only recovery and immediately clears session credentials`() = runTest {
        lateinit var emit: (LiveEvent) -> Unit
        var connects=0; var released=0
        val store=object : ApiKeyStore {
            override suspend fun load()="synthetic-key"
            override suspend fun save(key: String)=Unit
            override suspend fun forget()=Unit
        }
        val session=DictationSession(backgroundScope,recorder(),Transcriber { _, _ -> "unused" },
            object : PcmSource { override fun start(onAudio: (ByteArray)->Unit,onError: (Exception)->Unit)=Unit; override fun stop()=Unit },
            RealtimeTranscriber { _, sink -> emit=sink; connects++; object : LiveSession {
                override fun append(pcm: ByteArray)=Unit
                override fun finish()=Unit
                override fun cancel() { released++ }
            } },store)
        val feedback=FloatingFeedback()
        lateinit var capture: FloatingCapture
        capture=FloatingCapture(backgroundScope,session,editor(),{FloatingEnvironment(editor(),true,true,true,true,true)},
            {fail("eligible")},{fail("saved key")},{ state ->
                if(state.phase==Phase.RESULT) { feedback.offer(state.text,"Copy required"); capture.close() }
            }, mode=Mode.LIVE)
        capture.start(); runCurrent()
        emit(LiveEvent.Ready); runCurrent()
        emit(LiveEvent.Transcript("Retained final words",false,1)); runCurrent()
        session.stop(); runCurrent(); emit(LiveEvent.Finished); runCurrent()
        assertEquals("Retained final words",feedback.text)
        assertEquals("",session.state.value.key); assertEquals("",session.state.value.text)
        assertTrue(released>=1); assertEquals(1,connects)
        feedback.dismiss(); capture.close(); emit(LiveEvent.Ready); runCurrent()
        assertEquals("Retained final words",feedback.text); assertEquals(1,connects)
    }
    @Test fun `default floating Record captures locally and uploads only after explicit Stop`() = runTest {
        var starts=0; var uploads=0
        val file=File.createTempFile("floating-record-", ".m4a").apply { writeText("synthetic fixture") }
        val recorder=object : AudioRecorder {
            override fun start(onLimit:()->Unit) { starts++ }
            override fun stop()=file
            override fun cancel()=Unit
        }
        val session=DictationSession(backgroundScope,recorder,Transcriber { _, _ -> uploads++; "Recorded words" },
            object : PcmSource { override fun start(onAudio:(ByteArray)->Unit,onError:(Exception)->Unit)=fail("Record must not stream PCM"); override fun stop()=Unit },
            RealtimeTranscriber { _, _ -> error("Record must not connect a WebSocket") })
        session.setKey("synthetic-key")
        val capture=FloatingCapture(backgroundScope,session,editor(),{FloatingEnvironment(editor(),true,true,true,true,true)},
            {fail("eligible")},{fail("key")},{})
        try {
            capture.start(); runCurrent()
            assertEquals(Mode.RECORD,session.state.value.mode); assertEquals(Phase.RECORDING,session.state.value.phase)
            assertEquals(1,starts); assertEquals(0,uploads)
            session.stop(); runCurrent()
            assertEquals(1,uploads); assertEquals(Phase.RESULT,session.state.value.phase); assertEquals("Recorded words",session.state.value.text)
        } finally { capture.close(); file.delete() }
    }
    @Test fun `Record starts before delayed credential read and never reports the stale pre-start error`()=runTest {
        for(failedBeforeStart in listOf(false,true)) {
        val key=CompletableDeferred<String?>();var starts=0;var missing=0;val phases=mutableListOf<Phase>()
        val session=DictationSession(backgroundScope,object:AudioRecorder {
            override fun start(onLimit:()->Unit) {starts++}
            override fun stop():File=error("not stopping in this fixture")
            override fun cancel()=Unit
        },Transcriber {_,_->error("no upload before Stop")},keyStore=object:ApiKeyStore {
            override suspend fun load()=key.await()
            override suspend fun save(key:String)=Unit
            override suspend fun forget()=Unit
        })
        val capture=FloatingCapture(backgroundScope,session,editor(),{FloatingEnvironment(editor(),true,true,true,true,true)},
            {fail("eligible")},{missing++},{phases+=it.phase})
        try {
            runCurrent()
            if(failedBeforeStart) {key.completeExceptionally(java.io.IOException("storage"));runCurrent()}
            capture.start();runCurrent()
            assertEquals(1,starts);assertEquals(!failedBeforeStart,session.state.value.keyBusy);assertEquals(Phase.RECORDING,session.state.value.phase)
            if(!failedBeforeStart)key.completeExceptionally(java.io.IOException("storage"))
            runCurrent()
            assertEquals(0,missing);assertEquals(Phase.RECORDING,session.state.value.phase)
            assertFalse(phases.contains(Phase.ERROR))
        } finally {capture.close()}
        }
    }
    private fun recorder() = object : AudioRecorder {
        override fun start(onLimit: () -> Unit) = error("no file capture")
        override fun stop(): File = error("no file capture")
        override fun cancel() = Unit
    }
}
