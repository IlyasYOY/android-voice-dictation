package io.github.ilyasyoy.voicedictation.audio

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PcmRecordingRunTest {
    private class Driver : PcmRecordingDriver {
        val wrote = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var error = false
        @Volatile var block: CountDownLatch? = null
        override val bytesWritten get() = actions.count { it == "write" }.toLong() * 2048
        override fun read(buffer: ShortArray): Int {
            if (error) return -6
            buffer.indices.forEach { buffer[it] = if(it%2==0)4000 else -4000 }
            if (wrote.count == 0L) Thread.sleep(1)
            return buffer.size
        }
        override fun write(buffer: ShortArray, size: Int) { actions.add("write"); wrote.countDown(); block?.await(3, TimeUnit.SECONDS) }
        override fun stopInput() { actions.add("stopInput") }
        override fun finish() { actions.add("finish") }
        override fun close() { actions.add("close"); closed.countDown() }
    }
    private fun run(driver: Driver, limit: () -> Unit = {}, discard: () -> Unit = {}, maxSamples: Long = Long.MAX_VALUE, maxBytes: Long = Long.MAX_VALUE, released: () -> Unit = {}) =
        PcmRecordingRun(driver, maxSamples, maxBytes, limit, discard, released=released).also { it.start() }
    @Test fun stopClosesInputBeforeFinishingAndClearsMeter() {
        val driver = Driver(); val run = run(driver)
        assertTrue(driver.wrote.await(2, TimeUnit.SECONDS))
        run.stop()
        assertTrue(run.done)
        assertTrue(driver.actions.indexOf("stopInput") < driver.actions.indexOf("finish"))
        assertEquals("close", driver.actions.last())
        assertSame(VoiceLevels.Silence, run.current())
    }
    @Test fun cancellationClosesThenDiscardsWithoutFinishingOrCallingLimit() {
        val driver = Driver(); val limits = AtomicInteger(); val discarded = AtomicInteger()
        val run = run(driver, limit={limits.incrementAndGet()}, discard={assertEquals("close",driver.actions.last()); discarded.incrementAndGet()})
        assertTrue(driver.wrote.await(2, TimeUnit.SECONDS)); run.cancel()
        assertFalse(driver.actions.contains("finish")); assertEquals(0, limits.get()); assertEquals(1, discarded.get())
        assertSame(VoiceLevels.Silence, run.current())
    }
    @Test fun errorStopsMeterReleasesLeaseAndInvokesSafetyCallbackAfterCompletion() {
        val driver = Driver(); driver.error = true
        val callback = CountDownLatch(1); val release = AtomicInteger()
        val run = run(driver, limit={callback.countDown()}, released={release.incrementAndGet()})
        assertTrue(callback.await(2,TimeUnit.SECONDS)); assertTrue(run.done)
        assertEquals(1,release.get()); assertEquals("close",driver.actions.last())
        assertThrows(IOException::class.java) {run.stop()}
        assertSame(VoiceLevels.Silence,run.current())
    }
    @Test fun safetyLimitsFinalizeAndCallbackCannotDeadlockWithStop() {
        for ((samples,bytes) in listOf(2048L to Long.MAX_VALUE, Long.MAX_VALUE to 4096L)) {
            val driver=Driver(); val callback=CountDownLatch(1)
            val run=run(driver,limit={callback.countDown()},maxSamples=samples,maxBytes=bytes)
            assertTrue(callback.await(2,TimeUnit.SECONDS)); run.stop()
            assertTrue(driver.actions.contains("finish")); assertTrue(run.done)
        }
    }
    @Test fun timedOutCancellationNeverDiscardsLiveWriterAndCannotRepublishOldLevels() {
        val gate=CountDownLatch(1); val driver=Driver(); driver.block=gate
        val discarded=CountDownLatch(1); val run=run(driver,discard={assertEquals("close",driver.actions.last());discarded.countDown()})
        assertTrue(driver.wrote.await(2,TimeUnit.SECONDS)); run.cancel(10)
        assertFalse(run.done); assertEquals(1L,discarded.count)
        assertSame(VoiceLevels.Silence,run.current())
        gate.countDown(); assertTrue(discarded.await(2,TimeUnit.SECONDS))
        run.stop(); assertSame(VoiceLevels.Silence,run.current())
    }
    @Test fun stoppedRunCannotInterfereWithNewRun() {
        val first=Driver(); val old=run(first); assertTrue(first.wrote.await(2,TimeUnit.SECONDS)); old.cancel()
        val second=Driver(); val next=run(second); assertTrue(second.wrote.await(2,TimeUnit.SECONDS)); old.stop()
        assertFalse(next.done); next.stop(); assertSame(VoiceLevels.Silence,old.current())
    }
}
