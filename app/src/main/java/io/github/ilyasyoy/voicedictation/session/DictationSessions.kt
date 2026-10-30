package io.github.ilyasyoy.voicedictation.session

import android.content.Context
import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.CoroutineScope

/** Application composition root. Shared transports/storage; fresh audio and lifecycle per owner. */
internal object DictationSessions {
    private val fileTranscriber by lazy { OpenAiTranscriber() }
    private val realtimeClient by lazy { realtimeHttpClient() }
    private val realtime by lazy { OpenAiRealtimeTranscriber(realtimeClient) }
    private var keys: ApiKeyStore? = null
    @Synchronized private fun keys(context: Context): ApiKeyStore =
        keys ?: AndroidApiKeyStore(context.applicationContext).also { keys = it }

    fun create(context: Context, scope: CoroutineScope, keyStore: ApiKeyStore? = null,
               liveTranscriber: RealtimeTranscriber? = null): DictationSession {
        val app = context.applicationContext
        return DictationSession(scope, DeferredAudioRecorder { AndroidAudioRecorder(app) }, fileTranscriber,
            AndroidPcmSource(), liveTranscriber ?: realtime, keyStore ?: keys(app))
    }

    fun fixtureTransport(endpoint: String): RealtimeTranscriber = OpenAiRealtimeTranscriber(realtimeClient, endpoint)
}
