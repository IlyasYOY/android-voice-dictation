package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.DictationSession
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
            capture = FloatingCapture(backgroundScope, session, origin, { now }, { invalid++; capture.close() }, {}, {})
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
                { FloatingEnvironment(editor(), true, true, true, true, true) }, {}, {}, { states++ })
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
            { FloatingEnvironment(editor(), true, true, true, true, true) }, { fail("eligible") }, { fail("saved key") }, {})
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
            })
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
    private fun recorder() = object : AudioRecorder {
        override fun start(onLimit: () -> Unit) = error("no file capture")
        override fun stop(): File = error("no file capture")
        override fun cancel() = Unit
    }
}
