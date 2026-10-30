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
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var closed = false
    private var savedKey: String? = null
    private val mutableState = MutableStateFlow(DictationState(keyBusy = keyStore !== NoKeyStore))
    val state = mutableState.asStateFlow()
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
    private fun update(block: (DictationState) -> DictationState) { if (!closed) mutableState.value = block(mutableState.value) }
    fun setKey(key: String) { if (!closed && !state.value.busy) update { it.copy(key = key.trim(), keySaved = key.trim() == savedKey, problem = null) } }
    init {
        if (keyStore !== NoKeyStore) scope.launch {
            try {
                val loaded = keyStore.load()
                if (closed) return@launch
                savedKey = loaded
                update { it.copy(key = savedKey.orEmpty(), keySaved = savedKey != null, hasSavedKey = savedKey != null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error(Problem.STORAGE) }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun saveKey() {
        if (closed || state.value.busy || !state.value.validKey) return
        val key = state.value.key
        update { it.copy(keyBusy = true) }
        scope.launch {
            try { keyStore.save(key); if (closed) return@launch; savedKey = key; update { it.copy(keySaved = true, hasSavedKey = true, problem = null) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error(Problem.STORAGE) }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun forgetKey() {
        if (closed || state.value.keyBusy) return
        cancel(); update { it.copy(key = "", keySaved = false, keyBusy = true) }
        scope.launch {
            try { keyStore.forget(); savedKey = null; update { it.copy(hasSavedKey = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error(Problem.STORAGE) }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun setMode(mode: Mode) {
        if (closed || state.value.busy || state.value.canRetry) return
        update { it.copy(mode = mode, problem = null, phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT) }
    }
    fun edit(text: String) { if (!closed && !state.value.busy) update { it.copy(text = text, livePartial = false) } }
    fun clear() { if (!closed && !state.value.busy) { cancel(); update { it.copy(text = "", phase = Phase.IDLE, livePartial = false, completedSegments = 0) } } }
    fun permissionDenied() { if (!closed && !state.value.busy) error(Problem.PERMISSION) }
    fun start(hasPermission: Boolean) {
        if (closed || state.value.busy) return
        if (!state.value.validKey) { error(Problem.KEY); return }
        if (!hasPermission) { permissionDenied(); return }
        discardAudio()
        update { it.copy(problem = null, seconds = 0, canRetry = false, safetyStopped = false, livePartial = if (it.mode == Mode.LIVE) it.livePartial else false, completedSegments = 0) }
        if (state.value.mode == Mode.LIVE) { startLive(); return }
        val current = ++generation
        try {
            recorder.start { scope.launch { if (current == generation) safetyStop() } }
            update { it.copy(phase = Phase.RECORDING) }; startTicker()
        } catch (_: SecurityException) { runCatching { recorder.cancel() }; error(Problem.PERMISSION) }
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
                                update { it.copy(text = livePrefix + event.text, livePartial = livePrefixPartial || event.partial, completedSegments = event.completed) }
                            }
                            LiveEvent.Finished -> {
                                if (state.value.phase != Phase.FINALIZING) { liveFailure(InvalidTranscript()); continue }
                                releaseLive()
                                if (liveText.isBlank()) error(Problem.RESPONSE)
                                else update { it.copy(phase = Phase.RESULT) }
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
    private fun releaseLive() {
        ++generation
        request?.cancel(); request = null; ticker?.cancel(); ticker = null
        connectorJob?.cancel(); connectorJob = null
        events?.close(); events = null; eventJob?.cancel(); eventJob = null
        // Each port must get its cleanup even if another adapter/driver fails.
        runCatching { pcm?.stop() }
        runCatching { live?.cancel() }; live = null
    }
    private fun liveFailure(e: Exception, recording: Boolean = false) {
        releaseLive()
        error(if (recording && e !is SecurityException) Problem.RECORDING else problem(e))
    }
    fun stop() {
        if (closed || state.value.phase != Phase.RECORDING) return
        ticker?.cancel(); ticker = null
        if (state.value.mode == Mode.LIVE) {
            update { it.copy(phase = Phase.FINALIZING) }
            try { pcm?.stop() }
            catch (e: Exception) { liveFailure(e, recording = true); return }
            val current = generation
            request = scope.launch { delay(30_000); if (current == generation && state.value.phase == Phase.FINALIZING) liveFailure(IOException()) }
            try { live?.finish() } catch (e: Exception) { liveFailure(e) }
            return
        }
        update { it.copy(phase = Phase.TRANSCRIBING) }
        try { pendingAudio = recorder.stop(); upload() }
        catch (_: SecurityException) { runCatching { recorder.cancel() }; discardAudio(); error(Problem.PERMISSION) }
        catch (_: Exception) { runCatching { recorder.cancel() }; discardAudio(); error(Problem.RECORDING) }
    }
    fun retry() { if (!state.value.busy && state.value.canRetry && state.value.validKey) upload() }
    private fun upload() {
        val audio = pendingAudio ?: return
        val key = state.value.key
        val current = ++generation
        update { it.copy(phase = Phase.TRANSCRIBING, problem = null, canRetry = false) }
        request = scope.launch {
            try {
                val text = transcriber.transcribe(audio, key)
                if (current == generation) {
                    if (text.isBlank()) throw InvalidTranscript()
                    discardAudio(); update { it.copy(text = text, phase = Phase.RESULT, canRetry = false, livePartial = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) error(problem(e), retry = audio.exists()) }
        }
    }
    private fun problem(e: Exception) = when (e) {
        is SecurityException -> Problem.PERMISSION
        is ApiFailure -> when (e.status) { 401, 403 -> Problem.AUTH; 429 -> Problem.QUOTA; else -> Problem.SERVER }
        is InvalidTranscript -> Problem.RESPONSE
        is IOException -> Problem.NETWORK
        else -> Problem.RESPONSE
    }
    private fun error(problem: Problem, retry: Boolean = false) = update { it.copy(phase = Phase.ERROR, problem = problem, canRetry = retry) }
    fun cancel() {
        if (closed) return
        releaseLive()
        runCatching { recorder.cancel() }; discardAudio()
        update { it.copy(phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT, problem = null, canRetry = false, safetyStopped = false) }
    }
    private fun discardAudio() { pendingAudio?.delete(); pendingAudio = null }
    override fun close() {
        if (closed) return
        cancel()
        savedKey = null
        mutableState.value = state.value.copy(key = "", keySaved = false, hasSavedKey = false, keyBusy = false, text = "", livePartial = false, completedSegments = 0)
        closed = true
        scope.cancel()
    }
}
