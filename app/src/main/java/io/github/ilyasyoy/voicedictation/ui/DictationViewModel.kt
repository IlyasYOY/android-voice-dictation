package io.github.ilyasyoy.voicedictation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Activity lifecycle adapter; recording/network/storage orchestration lives in DictationSession. */
class DictationViewModel(createSession: (CoroutineScope) -> DictationSession) : ViewModel() {
    constructor(
        recorder: AudioRecorder,
        transcriber: Transcriber,
        pcm: PcmSource? = null,
        realtime: RealtimeTranscriber? = null,
        keyStore: ApiKeyStore = NoKeyStore,
        recordings:RecordingRetention=TemporaryRecordingRetention,
    ) : this({ scope -> DictationSession(scope, recorder, transcriber, pcm, realtime, keyStore,recordings=recordings) })

    private val session = createSession(viewModelScope)
    val state = session.state
    fun setKey(key: String) = session.setKey(key)
    fun setMode(mode: Mode) = session.setMode(mode)
    fun edit(text: String) = session.edit(text)
    fun clear() = session.clear()
    fun refreshDraft() = session.refreshDraft()
    fun permissionDenied() = session.permissionDenied()
    fun start(hasPermission: Boolean) = session.start(hasPermission)
    fun stop() = session.stop()
    fun retry() = session.retry()
    fun cancel() = session.cancel()
    fun takeRetryRecording(): File? = session.takeRetryRecording()
    fun background(): File? {
        val audio=session.takeRecordingForRecovery()
        session.cancel()
        return audio
    }
    fun saveKey() = session.saveKey()
    fun forgetKey() = session.forgetKey()
    override fun onCleared() { session.close() }
}
