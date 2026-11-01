package io.github.ilyasyoy.voicedictation.session

import android.content.Context
import java.io.File
import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Application composition root. Shared transports/storage; fresh audio and lifecycle per owner. */
internal object DictationSessions {
    private val fileTranscriber by lazy { OpenAiTranscriber() }
    private val realtimeClient by lazy { realtimeHttpClient() }
    private val realtime by lazy { OpenAiRealtimeTranscriber(realtimeClient) }
    private val fileMode = Transcriber { audio, key -> withContext(Dispatchers.IO) { fileTranscriber.transcribe(audio, key) } }
    private val liveMode = RealtimeTranscriber { key, emit ->
        val transport = withContext(Dispatchers.IO) { realtime }
        transport.connect(key, emit)
    }
    private var keys: ApiKeyStore? = null
    @Synchronized private fun keys(context: Context): ApiKeyStore =
        keys ?: AndroidApiKeyStore(context.applicationContext).also { keys = it }

    fun prepare(context: Context) {
        runCatching {AndroidRecordingStorage.repository(context)}
            .onSuccess {RecordingRecovery.processOwned.attach(it)}
            .onFailure {RecordingRecovery.processOwned.storageUnavailable()}
    }

    fun create(context: Context, scope: CoroutineScope, keyStore: ApiKeyStore? = null,
               liveTranscriber: RealtimeTranscriber? = null, initialMode: Mode = Mode.RECORD,
               fileTranscriber: Transcriber? = null, mainDraft:Boolean=false): DictationSession {
        val app = context.applicationContext
        prepare(app)
        return DictationSession(scope, DeferredAudioRecorder {prepare(app);AndroidAudioRecorder(app)}, fileTranscriber ?: fileMode,
            AndroidPcmSource(), liveTranscriber ?: liveMode, keyStore ?: keys(app), initialMode,
            StoredRecordingRetention({AndroidRecordingStorage.repository(app)},mainDraft))
    }

    fun fixtureTransport(endpoint: String) = RealtimeTranscriber { key, emit ->
        val transport = withContext(Dispatchers.IO) { OpenAiRealtimeTranscriber(realtimeClient, endpoint) }
        transport.connect(key, emit)
    }
    suspend fun transcribeRetained(context: Context, audio: File): String {
        val key=keys(context).load() ?: throw MissingSavedKey()
        return fileMode.transcribe(audio,key)
    }
}

internal class MissingSavedKey : java.io.IOException("Saved key unavailable")
