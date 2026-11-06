package io.github.ilyasyoy.voicedictation.data

import io.github.ilyasyoy.voicedictation.session.MissingSavedKey
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TextModelsTest {
    @Test fun explicitRefreshOnlyFiltersSupportedModelsAndDoesNotChangeTheChosenModel() = runTest {
        var calls = 0; val response = CompletableDeferred<List<String>>()
        val chosen = TextProcessingSettings(true, "gpt-4.1", "Keep this prompt")
        val list = TextModelList(backgroundScope) { calls++; response.await() }
        assertEquals(0, calls); assertEquals(TextModels.predefined, list.state.value.models)
        list.refresh(); list.refresh(); runCurrent(); assertEquals(1, calls)
        response.complete(listOf("gpt-6-luna", "gpt-6-luna", "whisper-1", "unknown-text-model")); runCurrent()
        assertEquals(listOf("gpt-6-luna"), list.state.value.models)
        assertTrue(list.state.value.updated); assertFalse(list.state.value.loading)
        assertEquals("gpt-4.1", chosen.model); assertEquals("Keep this prompt", chosen.prompt)
    }
    @Test fun failedPermissionNetworkAndMissingKeyRetainTheLastSuccessfulList() = runTest {
        var failure: Exception? = null; var calls = 0
        val list = TextModelList(backgroundScope) { calls++; failure?.let { throw it }; listOf("gpt-6-luna") }
        list.refresh(); runCurrent()
        for (error in listOf(ApiFailure(403), ApiFailure(401), IOException(), MissingSavedKey(), InvalidModelList())) {
            failure = error; list.refresh(); runCurrent()
            assertEquals(listOf("gpt-6-luna"), list.state.value.models)
            assertTrue(list.state.value.failed); assertFalse(list.state.value.loading)
        }
        assertEquals(6, calls)
    }
    @Test fun emptyAndCancelledRefreshNeverSelectAnAlternativeOrScheduleAnotherAttempt() = runTest {
        val response = CompletableDeferred<List<String>>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val list = TextModelList(scope) { calls++; response.await() }
        list.refresh(); runCurrent(); scope.cancel(); runCurrent()
        assertEquals(TextModels.predefined, list.state.value.models); assertFalse(list.state.value.loading)
        assertEquals(1, calls)
        val empty = TextModelList(backgroundScope) { emptyList() }; empty.refresh(); runCurrent()
        assertTrue(empty.state.value.models.isEmpty()); assertTrue(empty.state.value.updated)
    }
    @Test fun modelMetadataRequestUsesSavedCredentialAndExcludesAudioAndUnknownCapabilities() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = OpenAiTextModels(endpoint = server.url("/v1/models"))
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"gpt-6-luna"},{"id":"gpt-6.1-sol"},{"id":"gpt-transcribe"},{"id":"unknown-future-model"},{"id":"gpt-6-luna"}]}"""))
            assertEquals(listOf("gpt-6-luna", "gpt-6.1-sol"), client.list("synthetic"))
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method); assertEquals("/v1/models", request.path)
            assertEquals("Bearer synthetic", request.getHeader("Authorization")); assertEquals(0, request.bodySize)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun forbiddenAndServerDirectedRetryStayOneAttemptAndNeverExposeResponseDetails() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = OpenAiTextModels(endpoint = server.url("/v1/models"))
            for (status in listOf(401,403,429,503,307)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
                    .setHeader("Location", server.url("/must-not-follow")).setBody("private provider error details"))
                try { client.list("synthetic"); fail() } catch (error: ApiFailure) {
                    assertEquals(status, error.status); assertNull(error.message)
                }
            }
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun malformedMetadataAndUnsafeKeyNeverBecomeUsableChoices() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = OpenAiTextModels(endpoint = server.url("/v1/models"))
            try { client.list("unsafe\nkey"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
            for (body in listOf("{}", "not json", """{"object":"list","data":[{"id":42}]}""", """{"object":"list","data":[{"id":"bad\nname"}]}""")) {
                server.enqueue(MockResponse().setBody(body))
                try { client.list("synthetic"); fail() } catch (_: Exception) { }
            }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun cancellationClosesTheWaitingMetadataRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = OpenAiTextModels(endpoint = server.url("/v1/models"))
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}""").setBodyDelay(1, TimeUnit.SECONDS))
            val task = async { client.list("synthetic") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            task.cancelAndJoin(); assertTrue(task.isCancelled); assertEquals(1, server.requestCount)
        }
    }
}
