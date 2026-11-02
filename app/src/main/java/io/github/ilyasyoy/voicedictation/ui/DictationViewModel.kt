package io.github.ilyasyoy.voicedictation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.ilyasyoy.voicedictation.onboarding.SetupKeyDraft
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
    private var setup: io.github.ilyasyoy.voicedictation.onboarding.SetupFlow? = null
    internal fun setup(store: io.github.ilyasyoy.voicedictation.onboarding.SetupStore) =
        setup ?: io.github.ilyasyoy.voicedictation.onboarding.SetupFlow(store).also { setup = it }
    private val mutableSetupKey = MutableStateFlow(SetupKeyDraft())
    internal val setupKey = mutableSetupKey.asStateFlow()
    internal fun editSetupKey(text: String) {
        if (!state.value.busy && state.value.keyLoaded && state.value.keyStoreKnown && !state.value.hasSavedKey)
            mutableSetupKey.value = SetupKeyDraft(text.trim(), active = true)
    }
    internal fun saveSetupKey() {
        val draft = mutableSetupKey.value
        if (!draft.valid || state.value.busy || !state.value.keyLoaded || !state.value.keyStoreKnown || state.value.hasSavedKey) return
        mutableSetupKey.value = draft.copy(confirmed = true)
        session.setKey(draft.text); session.saveKey()
    }
    internal fun clearSetupKey() {
        val draft = mutableSetupKey.value
        if (draft.active && draft.confirmed) session.discardUnsavedKey(draft.text)
        mutableSetupKey.value = SetupKeyDraft()
    }
    internal fun setupKeyReady(): Boolean {
        val draft = mutableSetupKey.value
        if (draft.active) {
            val saved = draft.confirmed && state.value.keySaved && !state.value.keyBusy
            if (saved) clearSetupKey()
            return saved
        }
        return state.value.hasSavedKey || state.value.validKey
    }
    fun setKey(key: String) = session.setKey(key)
    fun setMode(mode: Mode) = session.setMode(mode)
    fun edit(text: String) = session.edit(text)
    fun draftSourceId():String? = session.draftSourceId()
    fun clear() = session.clear()
    fun refreshDraft() = session.refreshDraft()
    fun permissionDenied() = session.permissionDenied()
    fun checkHistoryCapacity() = session.checkHistoryCapacity()
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
