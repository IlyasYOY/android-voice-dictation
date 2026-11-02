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

/** One Activity-retained player/action owner for either presentation. No Activity or editor is retained. */
internal class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = runCatching { AndroidRecordingStorage.repository(application) }.getOrNull()
    val playback = AndroidRecordingPlayback.create(application)
    private val owner = RecordingRecovery.processOwned
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
        playback.stop()
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                owner.retry(id) { audio -> DebugFloatingConfiguration.transcribeRetained(context, audio)
                    ?: DictationSessions.transcribeRetained(context, audio) }?.let {
                    FloatingFeedback.processOwned.retain(it); FloatingFeedback.processOwned.resetSignal()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The ledger renders an honest generic error; never log content. */ }
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
