package io.github.ilyasyoy.voicedictation.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.DebugFloatingConfiguration
import io.github.ilyasyoy.voicedictation.floating.FloatingFeedback
import io.github.ilyasyoy.voicedictation.playback.AndroidRecordingPlayback
import io.github.ilyasyoy.voicedictation.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One Activity-retained player/action owner for either presentation. No Activity or editor is retained. */
internal class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // In-process instrumentation transport only, never configurable by intents or stored settings.
        internal var testProcess: (suspend (String, TextProcessingSettings) -> String?)? = null
    }
    val repository = runCatching { AndroidRecordingStorage.repository(application) }.getOrNull()
    val playback = AndroidRecordingPlayback.create(application)
    private val owner = RecordingRecovery.processOwned
    private val processingJob = mutableMapOf<String, Job>()
    private val mutableCancellableProcessing = MutableStateFlow<Set<String>>(emptySet())
    val cancellableProcessing = mutableCancellableProcessing.asStateFlow()
    private val mutableProcessingFailure = MutableStateFlow<String?>(null)
    val processingFailure = mutableProcessingFailure.asStateFlow()
    fun process(id: String) {
        if (processingJob[id]?.isActive == true) return
        playback.stop(); mutableProcessingFailure.value = null
        processingJob[id] = viewModelScope.launch {
            mutableCancellableProcessing.value += id
            try {
                val settings = TextProcessingPreferences(getApplication()).read().copy(enabled = true)
                runProcessing(id, settings)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                io.github.ilyasyoy.voicedictation.diagnostics.Diagnostics.failure(
                    io.github.ilyasyoy.voicedictation.diagnostics.DiagnosticArea.TEXT_PROCESSING,
                    io.github.ilyasyoy.voicedictation.diagnostics.DiagnosticAction.RETRY, error)
                mutableProcessingFailure.value = id
            }
            finally { mutableCancellableProcessing.value -= id }
        }
    }
    private suspend fun runProcessing(id: String, settings: TextProcessingSettings): String? {
        val local = testProcess
        return if (local != null) local(id, settings) else DictationSessions.processRetained(getApplication(), id, settings)
    }
    fun cancelProcessing(id: String) { processingJob[id]?.cancel() }
    init {
        viewModelScope.launch { playback.state.collectLatest { state ->
            while (state.playing) { delay(250); playback.tick() }
        } }
        viewModelScope.launch { owner.state.collect { state ->
            if (state.records.any { it.status == SavedRecordingStatus.CAPTURING } ||
                playback.state.value.id?.let { id -> state.records.none { it.id == id } } == true) playback.stop()
        } }
    }
    fun play(id: String) {
        if (owner.state.value.records.any { it.status == SavedRecordingStatus.CAPTURING }) return
        repository?.audio(id)?.let { AndroidRecordingPlayback.claim(playback); playback.toggle(id, it) }
    }
    fun retry(id: String) {
        if (processingJob[id]?.isActive == true) return
        playback.stop()
        processingJob[id] = viewModelScope.launch {
            mutableCancellableProcessing.value += id
            try {
                val context = getApplication<Application>()
                val settings = runCatching { TextProcessingPreferences(context).read() }
                owner.retry(id) { audio -> DebugFloatingConfiguration.transcribeRetained(context, audio)
                    ?: DictationSessions.transcribeRetained(context, audio) }?.let {
                    val options = settings.getOrThrow()
                    val text = if (options.enabled) runProcessing(id, options) ?: return@let else it
                    FloatingFeedback.processOwned.retain(text); FloatingFeedback.processOwned.resetSignal()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableProcessingFailure.value = id }
            finally { mutableCancellableProcessing.value -= id }
        }
    }
    fun delete(id: String) { playback.stop(); owner.discard(id) }
    fun copy(text: String) {
        val context = getApplication<Application>()
        val clip = ClipData.newPlainText(context.getString(R.string.transcript), text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
    fun background() = AndroidRecordingPlayback.release(playback)
    override fun onCleared() { background() }
}
