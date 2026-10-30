package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenAiTranscriberTest {
    private lateinit var server: MockWebServer
    private lateinit var audio: File
    private lateinit var api: OpenAiTranscriber
    @Before fun setup() {
        server = MockWebServer(); server.start()
        audio = File.createTempFile("speech", ".m4a").apply { writeBytes(byteArrayOf(0, 1, 2, 127)) }
        api = OpenAiTranscriber(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/v1/audio/transcriptions"))
    }
    @After fun cleanup() { server.shutdown(); audio.delete() }
    @Test fun `sends multipart bytes bearer model format and decodes unicode`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"text\":\" Привет 🌍 \",\"usage\":{}}"))
        assertEquals("Привет 🌍", api.transcribe(audio, "synthetic-test-key"))
        val req = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method); assertEquals("/v1/audio/transcriptions", req.path)
        assertEquals("Bearer synthetic-test-key", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = req.body.readByteArray()
        val text = body.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"model\"") && text.contains("\r\n\r\ngpt-transcribe\r\n"))
        assertTrue(text.contains("name=\"response_format\"") && text.contains("\r\n\r\njson\r\n"))
        assertTrue(text.contains("filename=\"dictation.m4a\"")); assertTrue(text.contains("Content-Type: audio/mp4"))
        assertTrue(body.toList().windowed(4).any { it == audio.readBytes().toList() })
    }
    @Test fun `api errors preserve status without disclosing server content or retrying`() = runBlocking {
        for (status in listOf(400, 401, 403, 429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("private error body"))
            try { api.transcribe(audio, "key"); fail() }
            catch (e: ApiFailure) { assertEquals(status, e.status); assertNull(e.message) }
        }
        assertEquals(6, server.requestCount)
    }
    @Test fun `rejects empty malformed and incorrectly typed text`() = runBlocking {
        for (body in listOf("{}", "{\"text\":\"   \"}", "not json", "{\"text\":42}", "")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.transcribe(audio, "key"); fail() } catch (_: Exception) { }
        }
    }
    @Test fun `empty audio and unsafe key never reach network`() = runBlocking {
        audio.writeText("")
        try { api.transcribe(audio, "key"); fail() } catch (_: IllegalArgumentException) { }
        audio.writeText("x")
        try { api.transcribe(audio, "key\ninjected"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun `cancellation terminates waiting request`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"text\":\"late\"}").setBodyDelay(2, TimeUnit.SECONDS))
        val task = async { api.transcribe(audio, "key") }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        task.cancelAndJoin(); assertTrue(task.isCancelled)
    }
    @Test fun `timeout is an IO failure and is not retried`() = runBlocking {
        val quick = OpenAiTranscriber(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).readTimeout(250, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build(), server.url("/v1/audio/transcriptions"))
        server.enqueue(MockResponse().setBody("{\"text\":\"late\"}").setBodyDelay(1, TimeUnit.SECONDS))
        try { quick.transcribe(audio, "key"); fail() } catch (_: java.io.IOException) { }
        assertEquals(1, server.requestCount)
    }
}
