package io.github.ilyasyoy.voicedictation.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes complete crypto + preference transactions, also across Activity/floating instances. */
internal class SerializedApiKeyStore(private val store: ApiKeyStore, private val mutex: Mutex) : ApiKeyStore {
    override suspend fun load(): String? = mutex.withLock { store.load() }
    override suspend fun save(key: String) = mutex.withLock { store.save(key) }
    override suspend fun forget() = mutex.withLock { store.forget() }
}
internal object ApiKeyStoreLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun namespace(name: String): Mutex = locks.getOrPut(name) { Mutex() }
}
