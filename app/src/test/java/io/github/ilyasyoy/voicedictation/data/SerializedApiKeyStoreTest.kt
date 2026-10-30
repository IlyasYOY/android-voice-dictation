package io.github.ilyasyoy.voicedictation.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedApiKeyStoreTest {
    @Test fun `Forget from another consumer cannot overtake an in-progress Save`() = runTest {
        val saved = CompletableDeferred<Unit>(); val order = mutableListOf<String>(); var value: String? = null
        val backing = object : ApiKeyStore {
            override suspend fun load() = value
            override suspend fun save(key: String) { order += "saving"; saved.await(); value = key; order += "saved" }
            override suspend fun forget() { value = null; order += "forgotten" }
        }
        val one = SerializedApiKeyStore(backing, ApiKeyStoreLocks.namespace("test-shared"))
        val two = SerializedApiKeyStore(backing, ApiKeyStoreLocks.namespace("test-shared"))
        val save = launch { one.save("synthetic-key") }; runCurrent()
        val forget = launch { two.forget() }; runCurrent(); assertEquals(listOf("saving"), order)
        saved.complete(Unit); save.join(); forget.join()
        assertEquals(listOf("saving", "saved", "forgotten"), order); assertNull(two.load())
    }
    @Test fun `blocked normal Save does not block a synthetic namespace transaction`() = runTest {
        val release = CompletableDeferred<Unit>()
        val backing = object : ApiKeyStore {
            override suspend fun load() = "synthetic-only"
            override suspend fun save(key: String) { release.await() }
            override suspend fun forget() = Unit
        }
        val normal = SerializedApiKeyStore(backing, ApiKeyStoreLocks.namespace("test-normal"))
        val synthetic = SerializedApiKeyStore(backing, ApiKeyStoreLocks.namespace(".test.crossapp"))
        val save = launch { normal.save("synthetic-normal-key") }; runCurrent()
        val load = async { synthetic.load() }; runCurrent()
        assertTrue(load.isCompleted); assertEquals("synthetic-only", load.await())
        release.complete(Unit); save.join()
    }
}
