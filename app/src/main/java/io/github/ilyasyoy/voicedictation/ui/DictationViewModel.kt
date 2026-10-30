package io.github.ilyasyoy.voicedictation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ilyasyoy.voicedictation.data.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Phase { IDLE, RECORDING, TRANSCRIBING, RESULT, ERROR }
enum class Problem { KEY, PERMISSION, RECORDING, NETWORK, AUTH, QUOTA, SERVER, RESPONSE }
data class DictationState(
    val phase: Phase = Phase.IDLE,
    val key: String = "",
    val text: String = "",
    val seconds: Int = 0,
    val problem: Problem? = null,
    val canRetry: Boolean = false,
) {
    val busy get() = phase == Phase.RECORDING || phase == Phase.TRANSCRIBING
    val validKey get() = key.isNotBlank() && key.none { it.isWhitespace() || it.code < 32 }
}

/** All actions and state transitions run on the main thread. Secrets never enter saved state. */
class DictationViewModel(private val recorder: AudioRecorder, private val transcriber: Transcriber) : ViewModel() {
    private val mutableState = MutableStateFlow(DictationState())
    val state = mutableState.asStateFlow()
    private var pendingAudio: File? = null
    private var request: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private fun update(block: (DictationState) -> DictationState) { mutableState.value = block(mutableState.value) }
    fun setKey(key: String) { if (!state.value.busy) update { it.copy(key = key.trim(), problem = null) } }
    fun edit(text: String) { if (!state.value.busy) update { it.copy(text = text) } }
    fun clear() { if (!state.value.busy) { cancel(); update { it.copy(text = "", phase = Phase.IDLE) } } }
    fun forgetKey() { cancel(); update { it.copy(key = "") } }
    fun permissionDenied() { if (!state.value.busy) error(Problem.PERMISSION) }
    fun start(hasPermission: Boolean) {
        if (state.value.busy) return
        if (!state.value.validKey) { error(Problem.KEY); return }
        if (!hasPermission) { permissionDenied(); return }
        discardAudio()
        try {
            recorder.start { if (state.value.phase == Phase.RECORDING) stop() }
            update { it.copy(phase = Phase.RECORDING, problem = null, seconds = 0, canRetry = false) }
            ticker = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    update { it.copy(seconds = it.seconds + 1) }
                    if (state.value.seconds >= 120) { stop(); break }
                }
            }
        } catch (_: SecurityException) { recorder.cancel(); error(Problem.PERMISSION) }
        catch (_: Exception) { recorder.cancel(); error(Problem.RECORDING) }
    }
    fun stop() {
        if (state.value.phase != Phase.RECORDING) return
        ticker?.cancel(); ticker = null
        // Change state before stopping hardware, so duplicate stop callbacks are harmless.
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
                    discardAudio(); update { it.copy(text = text, phase = Phase.RESULT, canRetry = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == generation) {
                    val problem = when (e) {
                        is ApiFailure -> when (e.status) { 401, 403 -> Problem.AUTH; 429 -> Problem.QUOTA; else -> Problem.SERVER }
                        is InvalidTranscript -> Problem.RESPONSE
                        is IOException -> Problem.NETWORK
                        else -> Problem.RESPONSE
                    }
                    error(problem, retry = audio.exists())
                }
            }
        }
    }
    private fun error(problem: Problem, retry: Boolean = false) = update {
        it.copy(phase = Phase.ERROR, problem = problem, canRetry = retry)
    }
    fun cancel() {
        ++generation
        request?.cancel(); request = null
        ticker?.cancel(); ticker = null
        recorder.cancel(); discardAudio()
        update { it.copy(phase = if (it.text.isBlank()) Phase.IDLE else Phase.RESULT, problem = null, canRetry = false) }
    }
    private fun discardAudio() { pendingAudio?.delete(); pendingAudio = null }
    override fun onCleared() { cancel() }
}
