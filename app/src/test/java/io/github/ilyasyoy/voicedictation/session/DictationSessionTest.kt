package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictationSessionTest {
    private class Rig {
        var starts = 0
        var stops = 0
        var cancels = 0
        var connects = 0
        var failStop = false
        var failStartInline = false
        var failFinish = false
        lateinit var emit: (LiveEvent) -> Unit
        val pcm = object : PcmSource {
            override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) { starts++; if (failStartInline) onError(java.io.IOException()) }
            override fun stop() { stops++; if (failStop) error("driver") }
        }
        val connection = object : LiveSession {
            override fun append(pcm: ByteArray) = Unit
            override fun finish() { if (failFinish) throw java.io.IOException("finish failed") }
            override fun cancel() { cancels++ }
        }
        val recorder = object : AudioRecorder {
            override fun start(onLimit: () -> Unit) = Unit
            override fun stop(): File = error("no file")
            override fun cancel() = Unit
        }
        fun session(scope: CoroutineScope, readyInline: Boolean = false, earlyEvents: Int = 0) = DictationSession(scope, recorder,
            Transcriber { _, _ -> error("no upload") }, pcm, RealtimeTranscriber { _, callback ->
                connects++; emit = callback
                if (readyInline) callback(LiveEvent.Ready)
                repeat(earlyEvents) { callback(LiveEvent.Transcript("partial", true, 0)) }
                connection
            }).apply { setKey("synthetic-key"); setMode(Mode.LIVE) }
    }
    @Test fun `inline Ready cannot be lost before connect returns`() = runTest {
        val rig = Rig()
        val session = rig.session(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), true)
        session.start(true); runCurrent()
        assertEquals(1, rig.starts); assertEquals(Phase.RECORDING, session.state.value.phase)
        session.close()
    }
    @Test fun `finished invalidates late and duplicate transport callbacks`() = runTest {
        val rig = Rig(); val session = rig.session(backgroundScope)
        session.start(true); rig.emit(LiveEvent.Ready); runCurrent(); session.stop()
        rig.emit(LiveEvent.Transcript("final", false, 1)); rig.emit(LiveEvent.Finished); runCurrent()
        val result = session.state.value
        rig.emit(LiveEvent.Transcript("late", false, 2)); rig.emit(LiveEvent.Ready); rig.emit(LiveEvent.Finished); runCurrent()
        assertEquals(result, session.state.value); assertEquals(1, rig.starts)
        session.close()
    }
    @Test fun `closed owner cannot reopen transport or retain a new credential`() = runTest {
        val rig = Rig(); val session = rig.session(backgroundScope)
        session.close(); session.setKey("another-secret"); session.start(true); runCurrent()
        assertEquals(0, rig.connects); assertEquals("", session.state.value.key)
    }
    @Test fun `cleanup failure in one port never prevents transport cleanup`() = runTest {
        val rig = Rig(); val session = rig.session(backgroundScope)
        session.start(true); rig.emit(LiveEvent.Ready); runCurrent()
        rig.failStop = true; session.cancel()
        assertEquals(1, rig.cancels); assertFalse(session.state.value.audioBusy)
        session.close()
    }
    @Test fun `inline microphone error cannot transition back to recording`() = runTest {
        val rig = Rig(); rig.failStartInline = true
        val session = rig.session(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), true)
        session.start(true); runCurrent()
        assertEquals(Phase.ERROR, session.state.value.phase); assertEquals(Problem.RECORDING, session.state.value.problem)
        assertEquals(1, rig.cancels); session.close()
    }
    @Test fun `callback flood before connect returns fails boundedly and cancels returned connection`() = runTest {
        val rig = Rig()
        val session = rig.session(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), true, 100)
        session.start(true); runCurrent()
        assertEquals(Phase.ERROR, session.state.value.phase); assertEquals(0, rig.starts); assertEquals(1, rig.cancels)
        session.close()
    }
    @Test fun `stale file-recorder limit cannot stop a subsequent recording`() = runTest {
        val callbacks = mutableListOf<() -> Unit>(); var stops = 0
        val recorder = object : AudioRecorder {
            override fun start(onLimit: () -> Unit) { callbacks += onLimit }
            override fun stop(): File { stops++; error("must stay recording") }
            override fun cancel() = Unit
        }
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> "unused" })
        session.setKey("synthetic-key"); session.start(true); session.cancel(); session.start(true)
        callbacks.first().invoke(); runCurrent()
        assertEquals(Phase.RECORDING, session.state.value.phase); assertEquals(0, stops); session.close()
    }
    @Test fun `finish transport exception is a network failure and still releases capture`() = runTest {
        val rig = Rig(); val session = rig.session(backgroundScope)
        session.start(true); rig.emit(LiveEvent.Ready); runCurrent(); rig.failFinish = true
        session.stop(); runCurrent()
        assertEquals(Problem.NETWORK, session.state.value.problem); assertFalse(session.state.value.audioBusy)
        assertEquals(1, rig.cancels); session.close()
    }
    @Test fun `Cancel while setup is suspended immediately settles and cancels a late connection`() = runTest {
        val rig = Rig(); lateinit var pending: Continuation<LiveSession>
        val session = DictationSession(backgroundScope, rig.recorder, Transcriber { _, _ -> "unused" }, rig.pcm,
            RealtimeTranscriber { _, _ -> suspendCoroutine { pending = it } })
        session.setKey("synthetic-key"); session.setMode(Mode.LIVE); session.start(true)
        assertEquals(Phase.CONNECTING, session.state.value.phase); session.cancel()
        assertEquals(Phase.IDLE, session.state.value.phase)
        pending.resume(rig.connection); runCurrent(); advanceTimeBy(15_000); runCurrent()
        assertEquals(1, rig.cancels); assertEquals(0, rig.starts); assertEquals(Phase.IDLE, session.state.value.phase)
        session.close()
    }
    @Test fun `setup deadline closes a connection returned after timeout without opening microphone`() = runTest {
        val rig = Rig(); lateinit var pending: Continuation<LiveSession>
        val session = DictationSession(backgroundScope, rig.recorder, Transcriber { _, _ -> "unused" }, rig.pcm,
            RealtimeTranscriber { _, _ -> suspendCoroutine { pending = it } })
        session.setKey("synthetic-key"); session.setMode(Mode.LIVE); session.start(true)
        advanceTimeBy(15_000); runCurrent(); assertEquals(Problem.NETWORK, session.state.value.problem)
        pending.resume(rig.connection); runCurrent()
        assertEquals(1, rig.cancels); assertEquals(0, rig.starts); session.close()
    }
    @Test fun `old setup failure cannot terminate an explicitly restarted session`() = runTest {
        val rig = Rig(); lateinit var old: Continuation<LiveSession>; var count = 0
        val session = DictationSession(backgroundScope, rig.recorder, Transcriber { _, _ -> "unused" }, rig.pcm,
            RealtimeTranscriber { _, emit ->
                if (count++ == 0) suspendCoroutine { old = it }
                else { emit(LiveEvent.Ready); rig.connection }
            })
        session.setKey("synthetic-key"); session.setMode(Mode.LIVE); session.start(true); session.cancel(); session.start(true); runCurrent()
        assertEquals(Phase.RECORDING, session.state.value.phase)
        old.resumeWithException(java.io.IOException("late setup failure")); runCurrent()
        assertEquals(Phase.RECORDING, session.state.value.phase); assertEquals(1, rig.starts); session.close()
    }
    @Test fun `state diagnostics redact both credentials and transcript`() {
        val state = DictationState(key = "private-credential", text = "private dictation")
        assertFalse(state.toString().contains(state.key)); assertFalse(state.toString().contains(state.text))
    }
}
