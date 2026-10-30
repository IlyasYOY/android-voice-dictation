package io.github.ilyasyoy.voicedictation.data

import io.github.ilyasyoy.voicedictation.domain.AudioTurnDetector
import io.github.ilyasyoy.voicedictation.domain.LiveTranscript
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject

sealed interface LiveEvent {
    data object Ready : LiveEvent
    data class Transcript(val text: String, val partial: Boolean, val completed: Int) : LiveEvent
    data object Finished : LiveEvent
    data class Failure(val error: Exception) : LiveEvent
}
interface LiveSession {
    fun append(pcm: ByteArray)
    fun finish()
    fun cancel()
}
fun interface RealtimeTranscriber { suspend fun connect(key: String, onEvent: (LiveEvent) -> Unit): LiveSession }

/** Ping timeout detects a stalled peer even when no transcript events are expected during silence. */
internal fun realtimeHttpClient(pingMillis: Long = 15_000): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
    .pingInterval(pingMillis, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false).build()

/** Direct key connection for the user's own device; never embeds or obtains a credential. */
class OpenAiRealtimeTranscriber(
    private val client: OkHttpClient = realtimeHttpClient(),
    private val endpoint: String = "wss://api.openai.com/v1/realtime?intent=transcription",
) : RealtimeTranscriber {
    override suspend fun connect(key: String, onEvent: (LiveEvent) -> Unit): LiveSession {
        require(key.isNotEmpty() && key.all { it.code in 33..126 })
        val acquired = AtomicReference<LiveSession?>()
        try {
            // Request/OkHttp initialization and cold class loading must not occupy the UI thread.
            val connected = withContext(Dispatchers.IO) {
                Connection(client, Request.Builder().url(endpoint).header("Authorization", "Bearer $key").build(), onEvent)
                    .also(acquired::set)
            }
            currentCoroutineContext().ensureActive()
            acquired.set(null) // Transfer to the caller only after a cancellation-safe dispatcher return.
            return connected
        } finally { acquired.getAndSet(null)?.cancel() }
    }

    private class Connection(client: OkHttpClient, request: Request, private val emit: (LiveEvent) -> Unit) : LiveSession {
        private val transcript = LiveTranscript()
        private val turns = AudioTurnDetector()
        private var ready = false
        private var terminal = false
        private var finishing = false
        private var buffered = 0
        private var commits = 0
        private val lock = Any()
        private var socket: WebSocket? = null
        init {
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                    socket = webSocket
                    if (terminal) { webSocket.cancel(); return@synchronized }
                    try { send(JSONObject().put("type", "session.update").put("session", JSONObject()
                        .put("type", "transcription").put("audio", JSONObject().put("input", JSONObject()
                            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                            .put("transcription", JSONObject().put("model", "gpt-live-transcribe").put("delay", "low"))
                            .put("turn_detection", JSONObject.NULL))))) } catch (_: Exception) { fail(IOException()) }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
                    if (terminal) return@synchronized
                    try {
                        if (text.length > 1_000_000) throw InvalidTranscript()
                        val event = JSONObject(text)
                        when (event.optString("type")) {
                            "session.updated" -> {
                                if (event.getJSONObject("session").getString("type") != "transcription") throw InvalidTranscript()
                                if (!ready) { ready = true; emit(LiveEvent.Ready) }
                            }
                            "input_audio_buffer.committed" -> {
                                transcript.committed(event.getString("item_id"), event.opt("previous_item_id") as? String)
                                publish()
                            }
                            "conversation.item.input_audio_transcription.delta" -> {
                                if (event.optInt("content_index", 0) == 0) {
                                    transcript.delta(event.getString("item_id"), event.getString("delta"), event.opt("event_id") as? String)
                                    publish()
                                }
                            }
                            "conversation.item.input_audio_transcription.completed" -> {
                                if (event.optInt("content_index", 0) == 0) {
                                    transcript.complete(event.getString("item_id"), event.getString("transcript")); publish(); checkFinished()
                                }
                            }
                            "conversation.item.input_audio_transcription.failed" -> fail(ApiFailure(500))
                            "error" -> {
                                val code = event.optJSONObject("error")?.optString("code")
                                fail(ApiFailure(when (code) { "invalid_api_key" -> 401; "insufficient_quota", "rate_limit_exceeded" -> 429; else -> 500 }))
                            }
                        }
                    } catch (_: Exception) { fail(InvalidTranscript()) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
                    fail(if (response != null) ApiFailure(response.code) else IOException(t))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
                    if (!terminal) fail(IOException())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
                    if (!terminal) fail(IOException())
                    webSocket.close(code, null); Unit
                }
            })
        }
        private fun send(event: JSONObject) {
            val ws = socket ?: throw IOException()
            if (ws.queueSize() > 2_000_000 || !ws.send(event.toString())) throw IOException()
        }
        private fun publish() {
            val text = transcript.text
            if (text.length > 500_000) throw InvalidTranscript()
            emit(LiveEvent.Transcript(text, transcript.partial, transcript.completedCount))
        }
        override fun append(pcm: ByteArray) = synchronized(lock) {
            if (terminal || finishing || !ready) return@synchronized
            try {
                require(pcm.isNotEmpty() && pcm.size % 2 == 0)
                send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.getEncoder().encodeToString(pcm)))
                buffered += pcm.size
                if (turns.append(pcm)) commit()
            } catch (_: Exception) { fail(IOException()) }
        }
        private fun commit() {
            if (buffered == 0) return
            if (buffered < 4800) {
                send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.getEncoder().encodeToString(ByteArray(4800 - buffered))))
            }
            send(JSONObject().put("type", "input_audio_buffer.commit"))
            commits++; buffered = 0; turns.reset()
        }
        override fun finish() = synchronized(lock) {
            if (terminal || finishing) return@synchronized
            try { finishing = true; commit(); checkFinished() } catch (_: Exception) { fail(IOException()) }
        }
        private fun checkFinished() {
            if (finishing && transcript.completedCount >= commits) {
                terminal = true; emit(LiveEvent.Finished); socket?.close(1000, null)
            }
        }
        private fun fail(error: Exception) {
            if (terminal) return
            terminal = true; socket?.cancel(); emit(LiveEvent.Failure(error))
        }
        override fun cancel() = synchronized(lock) { terminal = true; socket?.cancel(); Unit }
    }
}
