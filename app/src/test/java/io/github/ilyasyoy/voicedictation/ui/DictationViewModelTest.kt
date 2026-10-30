package io.github.ilyasyoy.voicedictation.ui

import io.github.ilyasyoy.voicedictation.data.AudioRecorder
import io.github.ilyasyoy.voicedictation.data.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DictationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var audio: File
    private var starts = 0
    private var cancels = 0
    private var calls = 0
    private var fail = false
    private var hang = false
    private var brokenRecorder = false
    private val recorder = object : AudioRecorder {
        override fun start(onLimit: () -> Unit) { starts++; if (brokenRecorder) error("hardware") }
        override fun stop(): File = audio
        override fun cancel() { cancels++; audio.delete() }
    }
    private fun model() = DictationViewModel(recorder, Transcriber { _, _ ->
        calls++
        if (hang) awaitCancellation()
        if (fail) throw java.io.IOException("offline")
        "Привет 🌍"
    })
    @Before fun setup() { Dispatchers.setMain(dispatcher); audio = File.createTempFile("audio", ".m4a").apply { writeText("audio") } }
    @After fun cleanup() { Dispatchers.resetMain(); audio.delete() }
    @Test fun `key and permission gate recording`() {
        val m = model(); m.start(true); assertEquals(0, starts)
        m.setKey("test-key"); m.start(false); assertEquals(0, starts)
        assertEquals(Problem.PERMISSION, m.state.value.problem)
    }
    @Test fun `record stop transcribe edits and clears with repeated taps guarded`() = runTest {
        val m = model(); m.setKey("test-key"); repeat(3) { m.start(true) }
        assertEquals(1, starts); assertEquals(Phase.RECORDING, m.state.value.phase)
        repeat(3) { m.stop() }; runCurrent()
        assertEquals(1, calls); assertEquals("Привет 🌍", m.state.value.text)
        assertEquals(Phase.RESULT, m.state.value.phase); assertFalse(audio.exists())
        m.edit("edited"); assertEquals("edited", m.state.value.text)
        m.clear(); assertEquals("", m.state.value.text)
    }
    @Test fun `network failure retains only retry audio until success`() = runTest {
        val m = model(); m.setKey("key"); m.start(true); fail = true; m.stop(); runCurrent()
        assertEquals(Problem.NETWORK, m.state.value.problem); assertTrue(m.state.value.canRetry); assertTrue(audio.exists())
        fail = false; repeat(3) { m.retry() }; runCurrent()
        assertEquals(2, calls); assertFalse(audio.exists()); assertFalse(m.state.value.canRetry)
    }
    @Test fun `leaving cancels upload and deletes audio and late result`() = runTest {
        val m = model(); m.setKey("key"); m.start(true); hang = true; m.stop(); runCurrent()
        m.cancel(); runCurrent()
        assertEquals(Phase.IDLE, m.state.value.phase); assertFalse(audio.exists()); assertEquals("", m.state.value.text)
    }
    @Test fun `cancel recording repeated is safe and preserves previous draft`() {
        val m = model(); m.edit("keep"); m.setKey("key"); m.start(true); repeat(3) { m.cancel() }
        assertEquals("keep", m.state.value.text); assertFalse(audio.exists()); assertTrue(cancels > 0)
    }
    @Test fun `revoked permission and recorder errors never leave recording active`() {
        val m = model(); m.setKey("key"); brokenRecorder = true; m.start(true)
        assertEquals(Problem.RECORDING, m.state.value.problem); assertEquals(Phase.ERROR, m.state.value.phase)
        assertFalse(audio.exists())
    }
    @Test fun `forgetting key cancels retry audio`() = runTest {
        val m = model(); m.setKey("key"); m.start(true); fail = true; m.stop(); runCurrent(); m.forgetKey()
        assertEquals("", m.state.value.key); assertFalse(audio.exists()); assertFalse(m.state.value.canRetry)
    }
    @Test fun `generous safety boundary stops without truncating recording`() = runTest {
        val m = model(); m.setKey("key"); m.start(true)
        advanceTimeBy(1_800_000); runCurrent()
        assertEquals(Phase.RESULT, m.state.value.phase); assertEquals(1, calls); assertFalse(audio.exists())
    }
    @Test fun `leaving an error discards retry and preserves existing text`() = runTest {
        val m = model(); m.setKey("key"); m.edit("keep"); m.start(true); fail = true; m.stop(); runCurrent()
        m.cancel(); assertEquals("keep", m.state.value.text); assertFalse(m.state.value.canRetry); assertFalse(audio.exists())
    }
    @Test fun `API status categories remain actionable with retained retry audio`() = runTest {
        val cases = listOf(401 to Problem.AUTH, 403 to Problem.AUTH, 429 to Problem.QUOTA, 500 to Problem.SERVER)
        for ((status, expected) in cases) {
            audio.writeText("audio")
            val m = DictationViewModel(recorder, Transcriber { _, _ -> throw io.github.ilyasyoy.voicedictation.data.ApiFailure(status) })
            m.setKey("key"); m.start(true); m.stop(); runCurrent()
            assertEquals(expected, m.state.value.problem); assertTrue(m.state.value.canRetry)
            m.cancel(); assertFalse(audio.exists())
        }
    }
    @Test fun `empty transcript preserves draft and offers explicit retry`() = runTest {
        val m = DictationViewModel(recorder, Transcriber { _, _ -> "   " })
        m.setKey("key"); m.edit("keep"); m.start(true); m.stop(); runCurrent()
        assertEquals(Problem.RESPONSE, m.state.value.problem); assertEquals("keep", m.state.value.text)
        assertTrue(m.state.value.canRetry); m.cancel()
    }
    @Test fun `saved key is restored and Forget removes storage`() = runTest {
        val store = object : io.github.ilyasyoy.voicedictation.data.ApiKeyStore {
            var stored: String? = null
            override suspend fun load() = stored
            override suspend fun save(key: String) { stored = key }
            override suspend fun forget() { stored = null }
        }
        val m = DictationViewModel(recorder, Transcriber { _, _ -> "text" }, keyStore = store)
        runCurrent(); m.setKey("synthetic-persist-test"); assertNull(store.stored)
        m.saveKey(); runCurrent(); assertTrue(m.state.value.keySaved)
        val restarted = DictationViewModel(recorder, Transcriber { _, _ -> "text" }, keyStore = store)
        runCurrent(); assertEquals("synthetic-persist-test", restarted.state.value.key)
        restarted.forgetKey(); runCurrent(); assertNull(store.stored); assertEquals("", restarted.state.value.key)
    }
    @Test fun `safety stop retains whole recording on network error for retry`() = runTest {
        val m = model(); m.setKey("key"); fail = true; m.start(true)
        advanceTimeBy(1_800_000); runCurrent()
        assertEquals(Problem.NETWORK, m.state.value.problem); assertTrue(audio.exists()); assertEquals("audio", audio.readText())
        assertTrue(m.state.value.canRetry); fail = false; m.retry(); runCurrent(); assertFalse(audio.exists())
    }
    @Test fun `failed key storage does not claim save success and Forget remains available`() = runTest {
        val store = object : io.github.ilyasyoy.voicedictation.data.ApiKeyStore {
            override suspend fun load(): String? = null
            override suspend fun save(key: String) { throw java.io.IOException() }
            override suspend fun forget() = Unit
        }
        val m = DictationViewModel(recorder, Transcriber { _, _ -> "text" }, keyStore = store)
        runCurrent(); m.setKey("synthetic-key"); m.saveKey(); runCurrent()
        assertEquals(Problem.STORAGE, m.state.value.problem); assertFalse(m.state.value.keySaved); assertFalse(m.state.value.keyBusy)
        m.forgetKey(); runCurrent(); assertEquals("", m.state.value.key)
    }
}
