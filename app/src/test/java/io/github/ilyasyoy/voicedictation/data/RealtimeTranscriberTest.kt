package io.github.ilyasyoy.voicedictation.data

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class RealtimeTranscriberTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var session: LiveSession? = null
    private val events = LinkedBlockingQueue<LiveEvent>()
    private val frames = LinkedBlockingQueue<JSONObject>()
    private lateinit var peer: WebSocket
    @Volatile private var stallAudio = false
    private val stalled = CountDownLatch(1)
    private val releasePeer = CountDownLatch(1)
    @Before fun setup() {
        server = MockWebServer(); server.start()
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text); frames.put(frame)
                if (stallAudio && frame.optString("type") == "input_audio_buffer.append") {
                    stalled.countDown(); releasePeer.await(5, TimeUnit.SECONDS) // TCP stays open; peer cannot read/respond to ping.
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
    }
    @After fun cleanup() { releasePeer.countDown(); session?.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    private fun connect() {
        session = OpenAiRealtimeTranscriber(client, server.url("/v1/realtime?intent=transcription").toString()).connect("synthetic-key", events::add)
        val config = frames.poll(5, TimeUnit.SECONDS)!!
        assertEquals("session.update", config.getString("type"))
        val settings = config.getJSONObject("session")
        assertEquals("transcription", settings.getString("type"))
        val input = settings.getJSONObject("audio").getJSONObject("input")
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals("gpt-live-transcribe", input.getJSONObject("transcription").getString("model"))
        assertTrue(input.isNull("turn_detection"))
        peer.send("{\"type\":\"session.updated\",\"session\":{\"type\":\"transcription\"}}")
        assertEquals(LiveEvent.Ready, events.poll(5, TimeUnit.SECONDS))
    }
    @Test fun `real websocket sends PCM before stop and reconciles partial final`() {
        connect()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/realtime?intent=transcription", request.path)
        assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
        val audio = ByteArray(4800) { (it % 127).toByte() }
        session!!.append(audio)
        val frame = frames.poll(5, TimeUnit.SECONDS)!!
        assertEquals("input_audio_buffer.append", frame.getString("type"))
        assertArrayEquals(audio, Base64.getDecoder().decode(frame.getString("audio")))
        peer.send("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"one\",\"event_id\":\"d1\",\"delta\":\"Hello\"}")
        assertEquals("Hello", (events.poll(5, TimeUnit.SECONDS) as LiveEvent.Transcript).text)
        session!!.finish()
        assertEquals("input_audio_buffer.commit", frames.poll(5, TimeUnit.SECONDS)!!.getString("type"))
        peer.send("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"one\",\"previous_item_id\":null}")
        peer.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"one\",\"transcript\":\"Hello world.\"}")
        var last: LiveEvent.Transcript? = null
        repeat(3) {
            when (val event = events.poll(5, TimeUnit.SECONDS)) {
                is LiveEvent.Transcript -> last = event
                LiveEvent.Finished -> { assertEquals("Hello world.", last!!.text); assertFalse(last!!.partial); return }
                else -> fail("Expected transcript or finish")
            }
        }
        fail("Missing completion")
    }
    @Test fun `cancel closes socket and ignores late transcript`() {
        connect(); session!!.cancel()
        peer.send("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"late\",\"delta\":\"ignore\"}")
        assertNull(events.poll(250, TimeUnit.MILLISECONDS))
    }
    @Test fun `provider error is categorized without exposing message`() {
        connect()
        peer.send("{\"type\":\"error\",\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private provider text\"}}")
        val failure = events.poll(5, TimeUnit.SECONDS) as LiveEvent.Failure
        assertEquals(429, (failure.error as ApiFailure).status); assertNull(failure.error.message)
    }
    @Test fun `disconnect is a visible failure and is not reconnected automatically`() {
        connect(); peer.close(1000, "test disconnect")
        assertTrue(events.poll(5, TimeUnit.SECONDS) is LiveEvent.Failure)
        assertEquals(1, server.requestCount)
    }
    @Test fun `half open peer times out by heartbeat without automatic reconnect`() {
        client.dispatcher.executorService.shutdown()
        client = realtimeHttpClient(100)
        connect(); stallAudio = true
        session!!.append(ByteArray(4800))
        assertTrue(stalled.await(2, TimeUnit.SECONDS))
        assertTrue("Stalled peer must produce a bounded failure", events.poll(3, TimeUnit.SECONDS) is LiveEvent.Failure)
        assertEquals(1, server.requestCount)
        assertEquals(15_000, realtimeHttpClient().pingIntervalMillis)
    }
}
