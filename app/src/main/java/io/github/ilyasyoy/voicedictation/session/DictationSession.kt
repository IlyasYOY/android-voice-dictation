package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.diagnostics.*

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.domain.AudioPolicy
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shared recording/transcription owner for Activity and AccessibilityService.
 * Commands and state transitions belong to the parent's serial (production: Main) dispatcher.
 * Owns a child scope; close is terminal, cancel preserves the editable draft and saved-key UI.
 */
class DictationSession(
    parentScope: CoroutineScope,
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber,
    private val keyStore: ApiKeyStore = NoKeyStore,
    private val recordings: RecordingRetention = TemporaryRecordingRetention,
    private val processingSettings: () -> TextProcessingSettings = { TextProcessingSettings() },
    private val textProcessor: TextProcessor = OpenAiTextProcessor(),
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var closed = false
    private var savedKey: String? = null
    private var keyProblem: Problem? = null
    private val restoredDraft=runCatching {recordings.restoreDraft()}
    private val draft=restoredDraft.getOrNull().orEmpty()
    private val mutableState = MutableStateFlow(DictationState(voiceLevels = recorder.levels, keyBusy = keyStore !== NoKeyStore, keyLoaded = keyStore === NoKeyStore, keyStoreKnown = keyStore === NoKeyStore,
        text=draft,phase=if(draft.isBlank())Phase.IDLE else Phase.RESULT,problem=if(restoredDraft.isFailure)Problem.LOCAL_STORAGE else null))
    val state = mutableState.asStateFlow()
    private var unpersistedDraft = false
    private var problemBeforeDraftFailure: Problem? = null
    private var pendingAudio: File? = null
    private var request: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var processingOptions = TextProcessingSettings()
    private fun update(block: (DictationState) -> DictationState) {
        if (closed) return
        val previous = mutableState.value
        val next = block(previous)
        mutableState.value = next
        if (next.problem != null && next.problem != previous.problem)
            Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.PROCESS, DiagnosticCode.valueOf(next.problem.name))
        if (next.phase != previous.phase) when (next.phase) {
            Phase.RECORDING -> Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.RECORD)
            Phase.TRANSCRIBING, Phase.PROCESSING -> Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.PROCESS)
            Phase.RESULT -> Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.STOP)
            else -> Unit
        }
    }
    fun setKey(key: String) { if (!closed && !state.value.busy) update { it.copy(key = key.trim(), keySaved = key.trim() == savedKey, problem = null) } }
    init {
        if (keyStore !== NoKeyStore) scope.launch {
            try {
                val loaded = keyStore.load()
                if (closed) return@launch
                keyProblem=null
                savedKey = loaded
                update { it.copy(key = savedKey.orEmpty(), keySaved = savedKey != null, hasSavedKey = savedKey != null, keyStoreKnown = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { keyStorageError() }
            finally { update { it.copy(keyBusy = false, keyLoaded = true) } }
        }
    }
    fun saveKey() = persistKey(state.value.key)
    /** An onboarding candidate becomes an active credential only after encrypted persistence succeeds. */
    internal fun saveSetupKey(candidate: String) {
        if (!state.value.keyLoaded || !state.value.keyStoreKnown || state.value.hasSavedKey) return
        persistKey(candidate.trim())
    }
    private fun persistKey(key: String) {
        if (closed || state.value.busy || key.isEmpty() || key.any { it.code !in 33..126 }) return
        update { it.copy(keyBusy = true) }
        scope.launch {
            try { keyStore.save(key); if (closed) return@launch; keyProblem=null; savedKey = key; update { it.copy(key = key, keySaved = true, hasSavedKey = true, keyStoreKnown = true, problem = null) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { keyStorageError() }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun forgetKey() {
        if (closed || state.value.keyBusy) return
        cancel(); update { it.copy(key = "", keySaved = false, keyBusy = true) }
        scope.launch {
            try { keyStore.forget(); keyProblem=null; savedKey = null; update { it.copy(hasSavedKey = false, keyStoreKnown = true) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { keyStorageError() }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun edit(text: String) { if (!closed && !state.value.busy) {update { it.copy(text = text) };persistDraft(text)} }
    fun draftSourceId():String? = recordings.draftSourceId()
    fun clear() { if (!closed && !state.value.busy) { cancel(); if(clearDraft())update { it.copy(text = "", phase = Phase.IDLE) } } }
    fun refreshDraft() {
        if(closed || state.value.audioBusy || state.value.canRetry)return
        try {
            // A failed checkpoint cannot authorize replacing newer in-memory edits. A
            // confirmed deletion still invalidates its source, including a dirty edit.
            if (unpersistedDraft && recordings.draftSourceAvailable()) return
            recordings.restoreDraft()?.let { text ->
                draftPersisted()
                update { it.copy(text=text,phase=if(text.isBlank())Phase.IDLE else Phase.RESULT) }
            }
        }
        catch(_:Exception) {update {it.copy(problem=Problem.LOCAL_STORAGE)}}
    }
    private fun persistDraft(text:String):Boolean = try {recordings.saveDraft(text);draftPersisted();true}
        catch(_:Exception) {draftFailed();false}
    private fun clearDraft():Boolean = try {recordings.clearDraft();draftPersisted();true}
        catch(_:Exception) {draftFailed();false}
    private fun draftFailed() {
        if (!unpersistedDraft) problemBeforeDraftFailure = state.value.problem
        unpersistedDraft = true
        update { it.copy(problem=Problem.LOCAL_STORAGE) }
    }
    private fun draftPersisted() {
        if (unpersistedDraft && state.value.problem == Problem.LOCAL_STORAGE) update { it.copy(problem=problemBeforeDraftFailure) }
        unpersistedDraft = false; problemBeforeDraftFailure = null
    }
    fun permissionDenied() { if (!closed && !state.value.audioBusy) error(Problem.PERMISSION) }
    fun checkHistoryCapacity(): Boolean = try {
        recordings.checkCapacity()
        update { it.copy(capacityBlocked = false) }
        true
    } catch (_: HistoryCapacityReached) {
        update { it.copy(capacityBlocked = true) }; false
    } catch (_: Exception) { error(Problem.LOCAL_STORAGE); false }
    private fun capacityReached() { Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.STORAGE, DiagnosticCode.CAPACITY); update { it.copy(capacityBlocked = true) } }
    fun start(hasPermission: Boolean) {
        if (closed || state.value.audioBusy || state.value.canRetry) return
        if (!checkHistoryCapacity()) return
        if (!hasPermission) { permissionDenied(); return }
        if (!discardAudio()) { error(Problem.LOCAL_STORAGE); return }
        try { recordings.beginSession() }
        catch (_: HistoryCapacityReached) { capacityReached(); return }
        catch (_: Exception) { error(Problem.LOCAL_STORAGE); return }
        update { it.copy(problem = null, seconds = 0, canRetry = false, safetyStopped = false) }
        update { it.copy(originalText = "", processedText = "", processingFailed = false) }
        val current = ++generation
        try {
            recorder.start { scope.launch { if (current == generation) safetyStop() } }
            update { it.copy(phase = Phase.RECORDING) }; startTicker()
        } catch (_: HistoryCapacityReached) { capacityReached() }
        catch (e: RecordingStorageFailure) { runCatching { recorder.cancel() }; error(Problem.LOCAL_STORAGE) }
        catch (_: SecurityException) { runCatching { recorder.cancel() }; error(Problem.PERMISSION) }
        catch (_: Exception) { runCatching { recorder.cancel() }; error(Problem.RECORDING) }
    }
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(1000); update { it.copy(seconds = it.seconds + 1) }
                if (state.value.seconds >= AudioPolicy.MAX_SECONDS) { safetyStop(); break }
            }
        }
    }
    private fun safetyStop() {
        if (state.value.phase == Phase.RECORDING) { Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.SAFETY_STOP); update { it.copy(safetyStopped = true) }; stop() }
    }
    private fun releaseWork() {
        ++generation
        runCatching { recordings.endProcessing(interrupted = true) }
        request?.cancel(); request = null
        ticker?.cancel(); ticker = null
    }
    fun stop() {
        if (closed || state.value.phase != Phase.RECORDING) return
        ticker?.cancel(); ticker = null
        update { it.copy(phase = Phase.TRANSCRIBING) }
        try { pendingAudio = recorder.stop(); recordings.stopped(checkNotNull(pendingAudio),state.value.seconds); upload() }
        catch (_: RecordingStorageFailure) {error(Problem.LOCAL_STORAGE,retry=pendingAudio?.exists()==true)}
        catch (_: SecurityException) { runCatching { recorder.cancel() }; discardAudio(); error(Problem.PERMISSION) }
        catch (_: Exception) { runCatching { recorder.cancel() }; discardAudio(); error(Problem.RECORDING) }
    }
    fun retry() { if (!state.value.busy && state.value.canRetry && state.value.validKey) { Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.RETRY); upload() } }
    /** Transfer failed-file ownership before closing an overlay session; no credential/editor travels. */
    fun takeRetryRecording(): File? {
        if(closed || state.value.audioBusy || !state.value.canRetry) return null
        return takeRecordingForRecovery()
    }
    /** Stop/cancel all work, transferring a local clip without uploading or deleting it. */
    fun takeRecordingForRecovery(): File? {
        if(closed) return null
        val recording=state.value.phase==Phase.RECORDING
        if(!recording && pendingAudio==null)return null
        releaseWork() // Invalidate a suspended/late upload before transferring its file.
        val audio=if(recording) try {recorder.stop()} catch(error:Exception) {Diagnostics.failure(DiagnosticArea.SESSION, DiagnosticAction.BACKGROUND, error);runCatching {recorder.cancel()};null} else pendingAudio
        if(recording && audio!=null)runCatching {recordings.stopped(audio,state.value.seconds)}.onFailure { Diagnostics.failure(DiagnosticArea.SESSION, DiagnosticAction.STORAGE, it) }
        val released=audio?.let { runCatching { recordings.release(it) }.isSuccess } ?: true
        pendingAudio=null
        update { it.copy(phase=if(it.text.isBlank())Phase.IDLE else Phase.RESULT,problem=if(released)null else Problem.LOCAL_STORAGE,canRetry=false) }
        return audio?.takeIf {it.exists()}
    }
    private fun upload() {
        val audio = pendingAudio ?: return
        val current = ++generation
        update { it.copy(phase = Phase.TRANSCRIBING, problem = null, canRetry = false) }
        request = scope.launch {
            var recognized = false
            try {
                // Snapshot once per explicit Stop/retry. Edits in Settings cannot mutate an in-flight request.
                // An unreadable optional setting cannot stop recognition, but must not silently
                // authorize paid processing or insert the raw fallback. Defer its error until saved.
                val options = runCatching { processingSettings().also { check(it.valid) } }
                // Stop ends local capture first. A local credential read may finish later; it never
                // delays Record start. This is the one Stop-requested attempt, not reconnect retry.
                val received=recordings.receivedText(audio)
                if(received==null && state.value.keyBusy)state.first { !it.keyBusy }
                if(current!=generation || closed)return@launch
                if(received==null && !state.value.validKey) {
                    val problem=keyProblem ?: Problem.KEY
                    recordings.failed(audio,problem)
                    error(problem,retry=audio.exists()); return@launch
                }
                val key=state.value.key
                recordings.beforeUpload(audio)
                val text = received ?: transcriber.transcribe(audio, key)
                if (current == generation) {
                    if (text.isBlank()) throw InvalidTranscript()
                    // A local commit failure must still leave the received words available to Copy.
                    update { it.copy(text=text, originalText=text, processingFailed=false, processedText="") }
                    recordings.completed(audio,text); pendingAudio=null
                    recognized = true
                    persistDraft(text)
                    processingOptions = options.getOrThrow()
                    if (processingOptions.enabled) {
                        // Never publish RESULT (the overlay insertion trigger) for the intermediate words.
                        recordings.beginProcessing(processingOptions)
                        update { it.copy(phase = Phase.PROCESSING) }
                        val processed = textProcessor.process(text, key, processingOptions)
                        if (current != generation || closed) return@launch
                        if (processed.isBlank()) throw InvalidTranscript()
                        recordings.completedProcessing(processed)
                        update { it.copy(text = processed, processedText = processed) }
                        persistDraft(processed)
                    }
                    update { it.copy(phase = Phase.RESULT, canRetry = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) {
                val problem=problem(e)
                if (recognized) {
                    runCatching { recordings.endProcessing() }
                    update { it.copy(phase = Phase.ERROR, problem = problem, canRetry = false, processingFailed = true) }
                    return@launch
                }
                val retained=runCatching { recordings.failed(audio,problem) }.isSuccess
                error(if(retained)problem else Problem.LOCAL_STORAGE,retry=audio.exists())
            } }
        }
    }
    private fun problem(e: Exception) = when (e) {
        is SecurityException -> Problem.PERMISSION
        is ApiFailure -> when (e.status) { 401, 403 -> Problem.AUTH; 429 -> Problem.QUOTA; else -> Problem.SERVER }
        is InvalidTranscript -> Problem.RESPONSE
        is RecordingStorageFailure, is VocabularyFailure -> Problem.LOCAL_STORAGE
        is IOException -> Problem.NETWORK
        else -> Problem.RESPONSE
    }
    private fun error(problem: Problem, retry: Boolean = false) = update { it.copy(phase = Phase.ERROR, problem = problem, canRetry = retry) }
    private fun keyStorageError() {
        keyProblem=Problem.STORAGE
        if(state.value.audioBusy) update {it.copy(problem=Problem.STORAGE)}
        else error(Problem.STORAGE)
    }
    fun cancel() {
        if (closed) return
        Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.CANCEL)
        persistDraft(state.value.text)
        releaseWork()
        runCatching { recorder.cancel() }.onFailure { Diagnostics.failure(DiagnosticArea.RECORDER, DiagnosticAction.CANCEL, it) }; val removed=discardAudio()
        update { it.copy(phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT, problem = if(removed && !unpersistedDraft)null else Problem.LOCAL_STORAGE, canRetry = false, safetyStopped = false, processingFailed = false) }
    }
    private fun discardAudio(): Boolean {
        val audio=pendingAudio ?: return true
        val removed=runCatching { recordings.discard(audio) }.isSuccess
        pendingAudio=null // Disk failure must not prevent microphone/key/scope cleanup on close.
        return removed
    }
    override fun close() {
        if (closed) return
        cancel()
        savedKey = null
        mutableState.value = state.value.copy(key = "", keySaved = false, hasSavedKey = false, keyBusy = false, text = "", originalText = "", processedText = "")
        Diagnostics.record(DiagnosticArea.SESSION, DiagnosticAction.CLOSE)
        closed = true
        scope.cancel()
    }
}
