package io.github.ilyasyoy.voicedictation.session

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared recording/transcription owner for Activity and AccessibilityService.
 * Commands and state transitions belong to the parent's serial (production: Main) dispatcher.
 * Owns a child scope; close is terminal, cancel preserves the editable draft and saved-key UI.
 */
class DictationSession(
    parentScope: CoroutineScope,
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber,
    private val pcm: PcmSource? = null,
    private val realtime: RealtimeTranscriber? = null,
    private val keyStore: ApiKeyStore = NoKeyStore,
    initialMode: Mode = Mode.RECORD,
    private val recordings: RecordingRetention = TemporaryRecordingRetention,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var closed = false
    private var savedKey: String? = null
    private var keyProblem: Problem? = null
    private val restoredDraft=runCatching {recordings.restoreDraft()}
    private val draft=restoredDraft.getOrNull().orEmpty()
    private val mutableState = MutableStateFlow(DictationState(mode = initialMode, keyBusy = keyStore !== NoKeyStore, keyLoaded = keyStore === NoKeyStore, keyStoreKnown = keyStore === NoKeyStore,
        text=draft,phase=if(draft.isBlank())Phase.IDLE else Phase.RESULT,problem=if(restoredDraft.isFailure)Problem.LOCAL_STORAGE else null))
    val state = mutableState.asStateFlow()
    private var unpersistedDraft = false
    private var problemBeforeDraftFailure: Problem? = null
    private var pendingAudio: File? = null
    private var request: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var live: LiveSession? = null
    private var events: Channel<LiveEvent>? = null
    private var eventJob: Job? = null
    private var connectorJob: Job? = null
    private var livePrefix = ""
    private var liveText = ""
    private var livePrefixPartial = false
    private var liveCheckpointSegments=-1
    private var liveLatestPartial=true
    private fun update(block: (DictationState) -> DictationState) { if (!closed) mutableState.value = block(mutableState.value) }
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
    fun setMode(mode: Mode) {
        if (closed || state.value.audioBusy || state.value.canRetry) return
        update { it.copy(mode = mode, problem = null, phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT) }
    }
    fun edit(text: String) { if (!closed && !state.value.busy) {update { it.copy(text = text, livePartial = false) };persistDraft(text)} }
    fun draftSourceId():String? = recordings.draftSourceId()
    fun clear() { if (!closed && !state.value.busy) { cancel(); if(clearDraft())update { it.copy(text = "", phase = Phase.IDLE, livePartial = false, completedSegments = 0) } } }
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
    private fun capacityReached() { update { it.copy(capacityBlocked = true) } }
    fun start(hasPermission: Boolean) {
        if (closed || state.value.audioBusy || state.value.canRetry || (state.value.mode==Mode.LIVE && state.value.keyBusy)) return
        if (!checkHistoryCapacity()) return
        if (state.value.mode==Mode.LIVE && !state.value.validKey) { error(Problem.KEY); return }
        if (!hasPermission) { permissionDenied(); return }
        if (!discardAudio()) { error(Problem.LOCAL_STORAGE); return }
        try { recordings.beginSession(state.value.mode) }
        catch (_: HistoryCapacityReached) { capacityReached(); return }
        catch (_: Exception) { error(Problem.LOCAL_STORAGE); return }
        update { it.copy(problem = null, seconds = 0, canRetry = false, safetyStopped = false, livePartial = if (it.mode == Mode.LIVE) it.livePartial else false, completedSegments = 0) }
        if (state.value.mode == Mode.LIVE) { startLive(); return }
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
        if (state.value.phase == Phase.RECORDING) { update { it.copy(safetyStopped = true) }; stop() }
    }
    private fun startLive() {
        val provider = realtime
        if (provider == null || pcm == null) { error(Problem.RECORDING); return }
        val current = ++generation
        livePrefixPartial = state.value.livePartial
        liveText = ""; livePrefix = state.value.text.takeIf(String::isNotBlank)?.plus("\n").orEmpty()
        liveCheckpointSegments=-1;liveLatestPartial=true
        update { it.copy(phase = Phase.CONNECTING) }
        request = scope.launch { delay(15_000); if (generation == current && state.value.phase == Phase.CONNECTING) liveFailure(IOException()) }
        val queue = Channel<LiveEvent>(64)
        events = queue
        val overflow = AtomicBoolean()
        connectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var unowned: LiveSession? = null
            try {
                // Providers may invoke callbacks inline. Buffer until the returned connection is owned.
                val connected = provider.connect(state.value.key) { event ->
                    val sent = queue.trySend(event)
                    if (sent.isFailure && !sent.isClosed && overflow.compareAndSet(false, true)) {
                        scope.launch { if (current == generation) liveFailure(IOException("Live event queue full")) }
                    }
                }.also { unowned = it }
                if (current != generation || closed || !currentCoroutineContext().isActive) { queue.close(); return@launch }
                live = connected
                unowned = null
                eventJob = scope.launch(start = CoroutineStart.LAZY) {
                    for (event in queue) {
                        if (current != generation) break
                        when (event) {
                            LiveEvent.Ready -> {
                                if (state.value.phase != Phase.CONNECTING) continue
                                request?.cancel(); request = null
                                try {
                                    pcm.start(connected::append) { failure ->
                                        scope.launch { if (current == generation) liveFailure(failure, recording = true) }
                                    }
                                    if (current != generation) continue
                                    update { it.copy(phase = Phase.RECORDING) }; startTicker()
                                } catch (e: Exception) { liveFailure(e, recording = true) }
                            }
                            is LiveEvent.Transcript -> {
                                if (state.value.phase !in setOf(Phase.RECORDING, Phase.FINALIZING)) continue
                                liveText = event.text
                                liveLatestPartial=event.partial
                                update { it.copy(text = livePrefix + event.text, livePartial = livePrefixPartial || event.partial, completedSegments = event.completed) }
                                if(event.completed>liveCheckpointSegments) {checkpointLive(true);liveCheckpointSegments=event.completed}
                            }
                            LiveEvent.Finished -> {
                                if (state.value.phase != Phase.FINALIZING) { liveFailure(InvalidTranscript()); continue }
                                releaseLive(endAdmission = false)
                                if (liveText.isBlank()) { runCatching { recordings.endSession() }; error(Problem.RESPONSE) }
                                else {
                                    val saved = try { checkpointLive(liveLatestPartial) } finally { runCatching { recordings.endSession() } }
                                    update {it.copy(phase=if(saved)Phase.RESULT else Phase.ERROR,problem=if(saved)null else Problem.LOCAL_STORAGE)}
                                    persistDraft(state.value.text)
                                }

                            }
                            is LiveEvent.Failure -> liveFailure(event.error)
                        }
                    }
                }
                eventJob?.start()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation && !closed) liveFailure(e) }
            finally { runCatching { unowned?.cancel() } }
        }
    }
    private fun releaseLive(endAdmission: Boolean = true) {
        ++generation
        request?.cancel(); request = null; ticker?.cancel(); ticker = null
        connectorJob?.cancel(); connectorJob = null
        events?.close(); events = null; eventJob?.cancel(); eventJob = null
        // Each port must get its cleanup even if another adapter/driver fails.
        runCatching { pcm?.stop() }
        runCatching { live?.cancel() }; live = null
        if (endAdmission) runCatching {recordings.endSession()}
    }
    private fun liveFailure(e: Exception, recording: Boolean = false) {
        releaseLive(endAdmission = false)
        val saved = try { checkpointLive(true) } finally { runCatching { recordings.endSession() } }
        persistDraft(state.value.text)
        error(if(!saved)Problem.LOCAL_STORAGE else if (recording && e !is SecurityException) Problem.RECORDING else problem(e))
    }
    private fun checkpointLive(partial:Boolean):Boolean = try {
        recordings.liveText(liveText,partial,state.value.seconds);true
    } catch(_:Exception) {update {it.copy(problem=Problem.LOCAL_STORAGE)};false}
    fun stop() {
        if (closed || state.value.phase != Phase.RECORDING) return
        ticker?.cancel(); ticker = null
        if (state.value.mode == Mode.LIVE) {
            checkpointLive(true);persistDraft(state.value.text)
            update { it.copy(phase = Phase.FINALIZING) }
            try { pcm?.stop() }
            catch (e: Exception) { liveFailure(e, recording = true); return }
            val current = generation
            request = scope.launch { delay(30_000); if (current == generation && state.value.phase == Phase.FINALIZING) liveFailure(IOException()) }
            try { live?.finish() } catch (e: Exception) { liveFailure(e) }
            return
        }
        update { it.copy(phase = Phase.TRANSCRIBING) }
        try { pendingAudio = recorder.stop(); recordings.stopped(checkNotNull(pendingAudio),state.value.seconds); upload() }
        catch (_: RecordingStorageFailure) {error(Problem.LOCAL_STORAGE,retry=pendingAudio?.exists()==true)}
        catch (_: SecurityException) { runCatching { recorder.cancel() }; discardAudio(); error(Problem.PERMISSION) }
        catch (_: Exception) { runCatching { recorder.cancel() }; discardAudio(); error(Problem.RECORDING) }
    }
    fun retry() { if (!state.value.busy && state.value.canRetry && state.value.validKey) upload() }
    /** Transfer failed-file ownership before closing an overlay session; no credential/editor travels. */
    fun takeRetryRecording(): File? {
        if(closed || state.value.audioBusy || !state.value.canRetry) return null
        return takeRecordingForRecovery()
    }
    /** Stop/cancel all work, transferring a local clip without uploading or deleting it. */
    fun takeRecordingForRecovery(): File? {
        if(closed || state.value.mode!=Mode.RECORD) return null
        val recording=state.value.phase==Phase.RECORDING
        if(!recording && pendingAudio==null)return null
        releaseLive() // Invalidate a suspended/late upload before transferring its file.
        val audio=if(recording) try {recorder.stop()} catch(_:Exception) {runCatching {recorder.cancel()};null} else pendingAudio
        if(recording && audio!=null)runCatching {recordings.stopped(audio,state.value.seconds)}
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
            try {
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
                    update { it.copy(text=text,livePartial=false) }
                    recordings.completed(audio,text); pendingAudio=null
                    update { it.copy(text = text, phase = Phase.RESULT, canRetry = false, livePartial = false) }
                    persistDraft(text)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) {
                val problem=problem(e)
                val retained=runCatching { recordings.failed(audio,problem) }.isSuccess
                error(if(retained)problem else Problem.LOCAL_STORAGE,retry=audio.exists())
            } }
        }
    }
    private fun problem(e: Exception) = when (e) {
        is SecurityException -> Problem.PERMISSION
        is ApiFailure -> when (e.status) { 401, 403 -> Problem.AUTH; 429 -> Problem.QUOTA; else -> Problem.SERVER }
        is InvalidTranscript -> Problem.RESPONSE
        is RecordingStorageFailure -> Problem.LOCAL_STORAGE
        is IOException -> Problem.NETWORK
        else -> Problem.RESPONSE
    }
    private fun error(problem: Problem, retry: Boolean = false) = update { it.copy(phase = Phase.ERROR, problem = problem, canRetry = retry) }
    private fun keyStorageError() {
        keyProblem=Problem.STORAGE
        if(state.value.mode==Mode.RECORD && state.value.audioBusy) update {it.copy(problem=Problem.STORAGE)}
        else error(Problem.STORAGE)
    }
    fun cancel() {
        if (closed) return
        if(state.value.mode==Mode.LIVE && state.value.audioBusy)checkpointLive(true)
        persistDraft(state.value.text)
        releaseLive()
        runCatching { recorder.cancel() }; val removed=discardAudio()
        update { it.copy(phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT, problem = if(removed && !unpersistedDraft)null else Problem.LOCAL_STORAGE, canRetry = false, safetyStopped = false) }
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
        mutableState.value = state.value.copy(key = "", keySaved = false, hasSavedKey = false, keyBusy = false, text = "", livePartial = false, completedSegments = 0)
        closed = true
        scope.cancel()
    }
}
