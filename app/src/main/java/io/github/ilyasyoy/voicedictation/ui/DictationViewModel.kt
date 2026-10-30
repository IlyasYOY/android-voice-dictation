package io.github.ilyasyoy.voicedictation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.domain.AudioPolicy
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Mode { RECORD, LIVE }
enum class Phase { IDLE, CONNECTING, RECORDING, TRANSCRIBING, FINALIZING, RESULT, ERROR }
enum class Problem { KEY, STORAGE, PERMISSION, RECORDING, NETWORK, AUTH, QUOTA, SERVER, RESPONSE }
data class DictationState(
    val mode: Mode = Mode.RECORD,
    val phase: Phase = Phase.IDLE,
    val key: String = "",
    val keySaved: Boolean = false,
    val keyBusy: Boolean = false,
    val hasSavedKey: Boolean = false,
    val text: String = "",
    val seconds: Int = 0,
    val problem: Problem? = null,
    val canRetry: Boolean = false,
    val livePartial: Boolean = false,
    val completedSegments: Int = 0,
    val safetyStopped: Boolean = false,
) {
    val audioBusy get() = phase in setOf(Phase.CONNECTING, Phase.RECORDING, Phase.TRANSCRIBING, Phase.FINALIZING)
    val busy get() = audioBusy || keyBusy
    val validKey get() = key.isNotEmpty() && key.all { it.code in 33..126 }
    val elapsed get() = "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** Main-thread state; key persistence happens only on explicit Save/Forget. No secrets in saved state. */
class DictationViewModel(
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber,
    private val pcm: PcmSource? = null,
    private val realtime: RealtimeTranscriber? = null,
    private val keyStore: ApiKeyStore = NoKeyStore,
) : ViewModel() {
    private var savedKey: String? = null
    private val mutableState = MutableStateFlow(DictationState(keyBusy = keyStore !== NoKeyStore))
    val state = mutableState.asStateFlow()
    private var pendingAudio: File? = null
    private var request: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var live: LiveSession? = null
    private var livePrefix = ""
    private var liveText = ""
    private var livePrefixPartial = false
    private fun update(block: (DictationState) -> DictationState) { mutableState.value = block(mutableState.value) }
    fun setKey(key: String) { if (!state.value.busy) update { it.copy(key = key.trim(), keySaved = key.trim() == savedKey, problem = null) } }
    init {
        if (keyStore !== NoKeyStore) viewModelScope.launch {
            try {
                savedKey = keyStore.load()
                update { it.copy(key = savedKey.orEmpty(), keySaved = savedKey != null, hasSavedKey = savedKey != null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error(Problem.STORAGE) }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun saveKey() {
        if (state.value.busy || !state.value.validKey) return
        val key = state.value.key
        update { it.copy(keyBusy = true) }
        viewModelScope.launch {
            try { keyStore.save(key); savedKey = key; update { it.copy(keySaved = true, hasSavedKey = true, problem = null) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error(Problem.STORAGE) }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun forgetKey() {
        if (state.value.keyBusy) return
        cancel(); update { it.copy(key = "", keySaved = false, keyBusy = true) }
        viewModelScope.launch {
            try { keyStore.forget(); savedKey = null; update { it.copy(hasSavedKey = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error(Problem.STORAGE) }
            finally { update { it.copy(keyBusy = false) } }
        }
    }
    fun setMode(mode: Mode) {
        if (state.value.busy || state.value.canRetry) return
        update { it.copy(mode = mode, problem = null, phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT) }
    }
    fun edit(text: String) { if (!state.value.busy) update { it.copy(text = text, livePartial = false) } }
    fun clear() { if (!state.value.busy) { cancel(); update { it.copy(text = "", phase = Phase.IDLE, livePartial = false, completedSegments = 0) } } }
    fun permissionDenied() { if (!state.value.busy) error(Problem.PERMISSION) }
    fun start(hasPermission: Boolean) {
        if (state.value.busy) return
        if (!state.value.validKey) { error(Problem.KEY); return }
        if (!hasPermission) { permissionDenied(); return }
        discardAudio()
        update { it.copy(problem = null, seconds = 0, canRetry = false, safetyStopped = false, livePartial = if (it.mode == Mode.LIVE) it.livePartial else false, completedSegments = 0) }
        if (state.value.mode == Mode.LIVE) { startLive(); return }
        try {
            recorder.start(::safetyStop)
            update { it.copy(phase = Phase.RECORDING) }; startTicker()
        } catch (_: SecurityException) { recorder.cancel(); error(Problem.PERMISSION) }
        catch (_: Exception) { recorder.cancel(); error(Problem.RECORDING) }
    }
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
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
        request = viewModelScope.launch { delay(15_000); if (generation == current && state.value.phase == Phase.CONNECTING) liveFailure(IOException()) }
        try {
            live = provider.connect(state.value.key) { event ->
                viewModelScope.launch {
                    if (current != generation) return@launch
                    when (event) {
                        LiveEvent.Ready -> {
                            if (state.value.phase != Phase.CONNECTING) return@launch
                            request?.cancel(); request = null
                            try {
                                val connection = live ?: return@launch
                                pcm.start(connection::append) { failure -> viewModelScope.launch { if (current == generation) liveFailure(failure, recording = true) } }
                                update { it.copy(phase = Phase.RECORDING) }; startTicker()
                            } catch (e: Exception) { liveFailure(e, recording = true) }
                        }
                        is LiveEvent.Transcript -> {
                            liveText = event.text
                            update { it.copy(text = livePrefix + event.text, livePartial = livePrefixPartial || event.partial, completedSegments = event.completed) }
                        }
                        LiveEvent.Finished -> {
                            request?.cancel(); request = null; ticker?.cancel(); pcm.stop(); live = null
                            if (liveText.isBlank()) error(Problem.RESPONSE)
                            else update { it.copy(phase = Phase.RESULT) }
                        }
                        is LiveEvent.Failure -> liveFailure(event.error)
                    }
                }
            }
        } catch (e: Exception) { liveFailure(e) }
    }
    private fun liveFailure(e: Exception, recording: Boolean = false) {
        ++generation; request?.cancel(); request = null; ticker?.cancel(); ticker = null
        pcm?.stop(); live?.cancel(); live = null
        error(if (recording && e !is SecurityException) Problem.RECORDING else problem(e))
    }
    fun stop() {
        if (state.value.phase != Phase.RECORDING) return
        ticker?.cancel(); ticker = null
        if (state.value.mode == Mode.LIVE) {
            update { it.copy(phase = Phase.FINALIZING) }
            pcm?.stop()
            val current = generation
            request = viewModelScope.launch { delay(30_000); if (current == generation && state.value.phase == Phase.FINALIZING) liveFailure(IOException()) }
            live?.finish(); return
        }
        update { it.copy(phase = Phase.TRANSCRIBING) }
        try { pendingAudio = recorder.stop(); upload() }
        catch (_: SecurityException) { recorder.cancel(); discardAudio(); error(Problem.PERMISSION) }
        catch (_: Exception) { recorder.cancel(); discardAudio(); error(Problem.RECORDING) }
    }
    fun retry() { if (!state.value.busy && state.value.canRetry && state.value.validKey) upload() }
    private fun upload() {
        val audio = pendingAudio ?: return
        val key = state.value.key
        val current = ++generation
        update { it.copy(phase = Phase.TRANSCRIBING, problem = null, canRetry = false) }
        request = viewModelScope.launch {
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
        ++generation; request?.cancel(); request = null; ticker?.cancel(); ticker = null
        pcm?.stop(); live?.cancel(); live = null
        recorder.cancel(); discardAudio()
        update { it.copy(phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT, problem = null, canRetry = false, safetyStopped = false) }
    }
    private fun discardAudio() { pendingAudio?.delete(); pendingAudio = null }
    override fun onCleared() { cancel() }
}
