package io.github.ilyasyoy.voicedictation.ui

import io.github.ilyasyoy.voicedictation.session.*

import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var emit: (LiveEvent) -> Unit
    private var starts = 0
    private var stops = 0
    private var finishes = 0
    private var canceled = 0
    private val pcm = object : PcmSource {
        override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) { starts++ }
        override fun stop() { stops++ }
    }
    private val session = object : LiveSession {
        override fun append(pcm: ByteArray) = Unit
        override fun finish() { finishes++ }
        override fun cancel() { canceled++ }
    }
    private val recorder = object : AudioRecorder {
        override fun start(onLimit: () -> Unit) = error("File recording not expected")
        override fun stop(): File = error("File recording not expected")
        override fun cancel() = Unit
    }
    private fun model() = DictationViewModel(recorder, Transcriber { _, _ -> error("No file API") }, pcm,
        RealtimeTranscriber { _, callback -> emit = callback; session }).apply { setKey("synthetic-key"); setMode(Mode.LIVE) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun `microphone starts only after session acknowledgement then stop waits for final`() = runTest {
        val m = model(); m.start(true); assertEquals(Phase.CONNECTING, m.state.value.phase); assertEquals(0, starts)
        emit(LiveEvent.Ready); runCurrent(); assertEquals(1, starts)
        emit(LiveEvent.Transcript("Hel", true, 0)); runCurrent(); assertEquals("Hel", m.state.value.text)
        m.stop(); assertEquals(Phase.FINALIZING, m.state.value.phase); assertEquals(1, finishes)
        emit(LiveEvent.Transcript("Hello.", false, 1)); emit(LiveEvent.Finished); runCurrent()
        assertEquals("Hello.", m.state.value.text); assertEquals(Phase.RESULT, m.state.value.phase); assertFalse(m.state.value.livePartial)
    }
    @Test fun `mode switching is guarded during connection and microphone work`() = runTest {
        val m = model(); m.start(true); m.setMode(Mode.RECORD); assertEquals(Mode.LIVE, m.state.value.mode)
        emit(LiveEvent.Ready); runCurrent(); m.setMode(Mode.RECORD); assertEquals(Mode.LIVE, m.state.value.mode)
        m.cancel(); m.setMode(Mode.RECORD); assertEquals(Mode.RECORD, m.state.value.mode)
    }
    @Test fun `cancel preserves received words ignores late callbacks and releases microphone`() = runTest {
        val m = model(); m.start(true); emit(LiveEvent.Ready); runCurrent()
        emit(LiveEvent.Transcript("Keep", true, 0)); runCurrent(); m.cancel()
        emit(LiveEvent.Transcript("late", false, 1)); runCurrent()
        assertEquals("Keep", m.state.value.text); assertTrue(stops > 0); assertTrue(canceled > 0)
    }
    @Test fun `disconnect preserves words and explicit new session appends without replay`() = runTest {
        val m = model(); m.start(true); emit(LiveEvent.Ready); runCurrent()
        emit(LiveEvent.Transcript("First.", false, 1)); emit(LiveEvent.Failure(java.io.IOException())); runCurrent()
        assertEquals(Phase.ERROR, m.state.value.phase); assertEquals("First.", m.state.value.text)
        assertFalse(m.state.value.audioBusy); assertTrue(stops > 0); assertTrue(canceled > 0)
        m.start(true); emit(LiveEvent.Ready); runCurrent(); emit(LiveEvent.Transcript("Second.", false, 1)); runCurrent()
        assertEquals("First.\nSecond.", m.state.value.text); m.cancel()
    }
    @Test fun `connect and finishing timeouts terminate network and microphone`() = runTest {
        val m = model(); m.start(true); advanceTimeBy(15_000); runCurrent()
        assertEquals(Problem.NETWORK, m.state.value.problem); assertEquals(0, starts)
        m.start(true); emit(LiveEvent.Ready); runCurrent(); m.stop(); advanceTimeBy(30_000); runCurrent()
        assertEquals(Problem.NETWORK, m.state.value.problem); assertTrue(stops > 0)
    }
    @Test fun `live long boundary uses elapsed time and finishes all audio`() = runTest {
        val m = model(); m.start(true); emit(LiveEvent.Ready); runCurrent()
        advanceTimeBy(1_800_000); runCurrent()
        assertEquals("30:00", m.state.value.elapsed); assertEquals(Phase.FINALIZING, m.state.value.phase)
        assertEquals(1, finishes); assertTrue(m.state.value.safetyStopped)
        m.cancel()
    }
    @Test fun `cancel while connecting ignores late ready without opening microphone`() = runTest {
        val m = model(); m.start(true); m.cancel(); emit(LiveEvent.Ready); runCurrent()
        assertEquals(0, starts); assertFalse(m.state.value.audioBusy)
    }
    @Test fun `live microphone permission is required before connection`() = runTest {
        val m = model(); m.start(false)
        assertEquals(Problem.PERMISSION, m.state.value.problem); assertEquals(0, starts)
    }
    @Test fun `empty final session reports no new text instead of reusing previous draft`() = runTest {
        val m = model(); m.edit("Previous draft"); m.start(true); emit(LiveEvent.Ready); runCurrent(); m.stop()
        emit(LiveEvent.Finished); runCurrent()
        assertEquals(Problem.RESPONSE, m.state.value.problem); assertEquals("Previous draft", m.state.value.text)
    }
}
