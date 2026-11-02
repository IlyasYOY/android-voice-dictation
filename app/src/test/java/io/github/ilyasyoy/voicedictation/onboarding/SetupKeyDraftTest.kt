package io.github.ilyasyoy.voicedictation.onboarding

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.io.IOException
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SetupKeyDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private val synthetic = "sk-test-fixture-" + "b".repeat(32)
    private var starts = 0
    private var transmissions = 0
    private lateinit var file: File
    private val recorder = object : AudioRecorder {
        override fun start(onLimit: () -> Unit) { starts++ }
        override fun stop() = file
        override fun cancel() = Unit
    }
    private class Store(var saved: String? = null, var failSave: Boolean = false, var failLoad: Boolean = false) : ApiKeyStore {
        var saves = 0; var forgets = 0
        override suspend fun load(): String? { if (failLoad) throw IOException("fixture"); return saved }
        override suspend fun save(key: String) { saves++; if (failSave) throw IOException("fixture"); saved = key }
        override suspend fun forget() { forgets++; saved = null }
    }
    private fun model(store: ApiKeyStore) = DictationViewModel(recorder, Transcriber { _, _ -> transmissions++; "synthetic words" }, keyStore = store)
    @Before fun before() { Dispatchers.setMain(dispatcher); file = File.createTempFile("onboarding", ".m4a").apply { writeText("synthetic bytes") } }
    @After fun after() { file.delete(); Dispatchers.resetMain() }
    @Test fun pastedCandidateIsStagedWithoutSavingOrBecomingATranscriptionCredential() = runTest {
        val store = Store(); val m = model(store); runCurrent()
        m.editSetupKey(synthetic)
        assertEquals(synthetic, m.setupKey.value.text); assertEquals("", m.state.value.key)
        assertFalse(m.setupKeyReady()); assertEquals(0, store.saves)
        assertEquals(0, starts); assertEquals(0, transmissions)
    }
    @Test fun deferringDiscardsUnconfirmedCandidateAndCannotSendItAtRecordStop() = runTest {
        val store = Store(); val m = model(store); runCurrent()
        m.editSetupKey(synthetic); m.clearSetupKey()
        m.start(true); assertEquals(1, starts)
        m.stop(); runCurrent()
        assertEquals("", m.state.value.key); assertEquals(0, transmissions); assertEquals(0, store.saves)
        assertEquals(Problem.KEY, m.state.value.problem)
    }
    @Test fun onlyExplicitSaveConfirmsCandidateAndUsesExistingKeyStore() = runTest {
        val store = Store(); val m = model(store); runCurrent()
        m.editSetupKey(synthetic); m.saveSetupKey(); runCurrent()
        assertEquals(1, store.saves); assertEquals(synthetic, store.saved)
        assertTrue(m.setupKeyReady()); assertEquals("", m.setupKey.value.text)
        assertEquals(0, transmissions); assertEquals(0, starts)
    }
    @Test fun existingSavedKeyIsNeverReplacedByOnboardingDraftOrSave() = runTest {
        val store = Store("synthetic-existing-key"); val m = model(store); runCurrent()
        m.editSetupKey(synthetic); m.saveSetupKey(); runCurrent()
        assertEquals("synthetic-existing-key", store.saved); assertEquals("synthetic-existing-key", m.state.value.key)
        assertEquals("", m.setupKey.value.text); assertEquals(0, store.saves); assertEquals(0, store.forgets)
        assertTrue(m.setupKeyReady())
    }
    @Test fun persistenceFailureKeepsKeyStepAndDoesNotPretendSaveSucceeded() = runTest {
        val store = Store(failSave = true); val m = model(store); runCurrent()
        m.editSetupKey(synthetic); m.saveSetupKey(); runCurrent()
        assertEquals(Problem.STORAGE, m.state.value.problem); assertFalse(m.state.value.keySaved)
        assertFalse(m.setupKeyReady()); assertEquals(synthetic, m.setupKey.value.text)
        assertNull(store.saved); assertEquals(0, transmissions)
    }
    @Test fun newProcessModelDoesNotRestoreUnsavedDraftFromMetadataOrKeyStore() = runTest {
        val store = Store(); val first = model(store); runCurrent(); first.editSetupKey(synthetic)
        val next = model(store); runCurrent()
        assertEquals("", next.setupKey.value.text); assertEquals("", next.state.value.key)
        assertEquals(0, store.saves); assertEquals(0, transmissions)
        assertFalse(first.setupKey.value.toString().contains(synthetic)); assertFalse(first.state.value.toString().contains(synthetic))
    }
    @Test fun slowInitialKeyReadDoesNotBlockOfflineRecordAndCannotAcceptClipboardDraftYet() = runTest {
        val loaded = CompletableDeferred<String?>()
        val store = object : ApiKeyStore {
            override suspend fun load() = loaded.await()
            override suspend fun save(key: String) = error("No save expected")
            override suspend fun forget() = Unit
        }
        val m = model(store); runCurrent()
        assertFalse(m.state.value.keyLoaded); m.editSetupKey(synthetic); assertEquals("", m.setupKey.value.text)
        m.start(true); assertEquals(Phase.RECORDING, m.state.value.phase); assertEquals(1, starts)
        loaded.complete(null); runCurrent(); assertTrue(m.state.value.keyLoaded); m.cancel()
        assertEquals(0, transmissions)
    }
    @Test fun loadFailureSettlesReadinessWithoutDeletingOrProbingExistingStore() = runTest {
        val store = Store("synthetic-existing-key", failLoad = true); val m = model(store); runCurrent()
        assertTrue(m.state.value.keyLoaded); assertFalse(m.state.value.keyBusy)
        assertEquals(Problem.STORAGE, m.state.value.problem); assertEquals("synthetic-existing-key", store.saved)
        assertEquals(0, store.saves); assertEquals(0, store.forgets); assertEquals(0, transmissions)
    }
}
