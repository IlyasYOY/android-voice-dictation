package io.github.ilyasyoy.voicedictation.diagnostics

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded, best effort diagnostics. Failures must never break recording or recurse into logging. */
internal object Diagnostics {
    @Volatile private var store: DiagnosticStore? = null
    internal val dropped = AtomicLong()
    internal val writeFailures = AtomicLong()
    private val writer = DiagnosticQueue()
    fun crash(error: Throwable) {
        failure(DiagnosticArea.APP, DiagnosticAction.CRASH, error)
        // Preserve the platform handler and cap best-effort flushing; an ANR/kill cannot be guaranteed.
        if (Thread.currentThread().name != "local-diagnostics") runCatching { writer.await(FutureTask { Unit }, 500) }
    }
    fun initialize(storage: DiagnosticStore) { store = storage; maintain() }
    fun record(area: DiagnosticArea, action: DiagnosticAction, code: DiagnosticCode = DiagnosticCode.OK) = record(DiagnosticEvent(area, action, code))
    fun failure(area: DiagnosticArea, action: DiagnosticAction, error: Throwable) = record(DiagnosticEvent.failure(area, action, error))
    fun record(event: DiagnosticEvent) {
        val target = store ?: return
        enqueue { target.append(event) }
    }
    fun maintain() { val target=store ?: return; enqueue { target.prune() } }
    private fun enqueue(block: () -> Unit) {
        try { writer.execute { try { block() } catch (_: Exception) { writeFailures.incrementAndGet() } } }
        catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }
    /** Called on IO. Barrier includes all accepted events before this export; no network/provider work on writer. */
    fun snapshot(): ByteArray {
        val target = checkNotNull(store)
        val task = FutureTask { target.snapshot() }
        return writer.await(task, 10_000)
    }
}

/** One running job plus a fixed queue. Tests can hold the worker to verify overload deterministically. */
internal class DiagnosticQueue(capacity: Int = 256) : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
        { task -> Thread(task, "local-diagnostics").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun execute(block: () -> Unit) = executor.execute(block)
    fun <T> await(task: FutureTask<T>, timeoutMillis: Long): T {
        executor.execute(task)
        try { return task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        finally { if (!task.isDone) { task.cancel(false); executor.remove(task) } }
    }
    override fun close() { executor.shutdownNow() }
}
