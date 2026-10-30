package io.github.ilyasyoy.voicedictation.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PcmCaptureWorkerTest {
    private class BlockedCapture : PcmCapture {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val readable = Semaphore(0)
        val releases = AtomicInteger()
        var releaseThread: Thread? = null
        override fun start() = Unit
        override fun read(buffer: ByteArray): Int { entered.countDown(); readable.acquireUninterruptibly(); return 2 }
        override fun stop() = Unit
        override fun release() { releaseThread = Thread.currentThread(); releases.incrementAndGet(); released.countDown() }
    }
    @Test fun `cancel restart cannot revive a blocked old capture or release it while read is in flight`() {
        val old = BlockedCapture(); val next = BlockedCapture(); var creates = 0
        val source = PcmCaptureWorker { if (creates++ == 0) old else next }
        val staleFrames = AtomicInteger(); val failures = AtomicInteger(); val newFrame = CountDownLatch(1)
        try {
            source.start({ staleFrames.incrementAndGet() }, { failures.incrementAndGet() })
            assertTrue(old.entered.await(3, TimeUnit.SECONDS)); source.stop()
            assertEquals(0, old.releases.get())
            source.start({ newFrame.countDown() }, { failures.incrementAndGet() })
            assertTrue(next.entered.await(3, TimeUnit.SECONDS))
            old.readable.release(); assertTrue(old.released.await(3, TimeUnit.SECONDS)); assertEquals(0, staleFrames.get())
            next.readable.release(); assertTrue(newFrame.await(3, TimeUnit.SECONDS)); source.stop(); source.stop()
            next.readable.release(4); assertTrue(next.released.await(3, TimeUnit.SECONDS))
            assertEquals(1, old.releases.get()); assertEquals(1, next.releases.get()); assertEquals(0, failures.get())
            assertNotSame(Thread.currentThread(), old.releaseThread); assertNotSame(Thread.currentThread(), next.releaseThread)
        } finally { source.stop(); old.readable.release(4); next.readable.release(4) }
    }
    @Test fun `driver read and stop failures still release once on capture thread`() {
        val released = CountDownLatch(1); val errors = AtomicInteger(); val releases = AtomicInteger()
        val source = PcmCaptureWorker { object : PcmCapture {
            override fun start() = Unit
            override fun read(buffer: ByteArray): Int = error("driver failed")
            override fun stop() = error("driver stop failed")
            override fun release() { releases.incrementAndGet(); released.countDown() }
        } }
        source.start({}, { errors.incrementAndGet() })
        assertTrue(released.await(3, TimeUnit.SECONDS)); source.stop()
        assertEquals(1, errors.get()); assertEquals(1, releases.get())
    }
}
