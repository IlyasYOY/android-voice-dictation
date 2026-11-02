package io.github.ilyasyoy.voicedictation.audio

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class StopTailTest {
    private class QueuedDriver(val tailFrames: Int = 1024, val tailWriteNanos: Long = 0, val rejectTail: Boolean = false) : PcmRecordingDriver {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val encoded = mutableListOf<Short>()
        val clock = AtomicLong()
        var reads = 0
        var microphone = true
        var drained = 0
        var finished = false
        var closed = false
        override val bytesWritten = 0L
        override fun read(buffer: ShortArray): Int {
            if (!microphone || reads >= 2) return 0
            val count = if (reads++ == 0) 1024 else tailFrames
            buffer.fill(reads.toShort(), 0, count)
            return count
        }
        override fun write(buffer: ShortArray, size: Int) {
            if (encoded.isEmpty()) {
                entered.countDown(); check(unblock.await(2,TimeUnit.SECONDS)); clock.addAndGet(500_000_000)
            } else {
                check(!microphone) { "Tail encoding must never prolong microphone capture" }
                if (rejectTail) throw IOException("Injected tail encoder failure")
                clock.addAndGet(tailWriteNanos)
            }
            repeat(size) { encoded.add(buffer[it]) }
        }
        override fun stopInput() { microphone = false }
        override fun stopInputAndDrain(consume: (ShortArray, Int) -> Unit) {
            drained++
            snapshotStopTail(ShortArray(tailFrames + 1), ::read, ::stopInput, consume)
        }
        override fun finish() { check(!microphone); finished = true }
        override fun close() { microphone = false; closed = true }
    }
    private fun start(driver: QueuedDriver, discard: () -> Unit = {}): PcmRecordingRun {
        val run=PcmRecordingRun(driver,Long.MAX_VALUE,Long.MAX_VALUE,{},discard,nanoTime=driver.clock::get)
        run.start(); assertTrue(driver.entered.await(2,TimeUnit.SECONDS)); return run
    }
    private fun stopDuringWrite(run: PcmRecordingRun, driver: QueuedDriver): Throwable? {
        val failure=AtomicReference<Throwable?>()
        val stop=Thread { try {run.stop()} catch(e:Throwable) {failure.set(e)} }
        stop.start()
        // The bounded completion wait is entered only after Stop has published its flag.
        val until=System.nanoTime()+1_000_000_000
        while(stop.state!=Thread.State.TIMED_WAITING && System.nanoTime()<until) Thread.yield()
        assertEquals(Thread.State.TIMED_WAITING,stop.state)
        driver.unblock.countDown(); stop.join(2000)
        assertFalse(stop.isAlive)
        return failure.get()
    }
    @Test fun stopDuringEncoderDelayPreservesAlreadyBufferedSpeechBeforeClosingMicrophone() {
        val driver=QueuedDriver(); val run=start(driver)
        assertNull(stopDuringWrite(run,driver))
        assertEquals(2048,driver.encoded.size)
        assertTrue(driver.encoded.take(1024).all {it==1.toShort()})
        assertTrue(driver.encoded.drop(1024).all {it==2.toShort()})
        assertEquals(1,driver.drained); assertTrue(driver.finished); assertTrue(driver.closed)
        assertSame(VoiceLevels.Silence,run.current())
    }
    @Test fun cancelDuringEncoderDelayDiscardsWithoutReadingOrEncodingQueuedTail() {
        val driver=QueuedDriver(); var discarded=0; val run=start(driver) {discarded++}
        run.cancel(1); driver.unblock.countDown(); run.stop()
        assertEquals(1024,driver.encoded.size); assertEquals(0,driver.drained)
        assertFalse(driver.finished); assertTrue(driver.closed); assertEquals(1,discarded)
    }
    @Test fun failedTailCannotBeReportedAsSuccessfulFinalization() {
        val driver=QueuedDriver(rejectTail=true); var discarded=0; val run=start(driver) {discarded++}
        val failure=stopDuringWrite(run,driver)
        assertTrue(failure is IOException); assertFalse(driver.finished); assertTrue(driver.closed)
        assertEquals(0,discarded); assertSame(VoiceLevels.Silence,run.current())
    }
    @Test fun slowTailEncodingIsBoundedAndCannotClaimSuccessfulCompleteAudio() {
        val driver=QueuedDriver(tailFrames=4096,tailWriteNanos=600_000_000)
        val run=start(driver)
        val failure=stopDuringWrite(run,driver)
        assertTrue(failure is IOException); assertTrue(checkNotNull(failure?.message).contains("tail"))
        assertTrue(driver.encoded.size < 5120); assertFalse(driver.finished); assertTrue(driver.closed)
    }
    @Test fun saturatedSnapshotFailsAndStillStopsMicAndClearsPcm() {
        val buffer=ShortArray(4) {2}; var stopped=false; var consumed=false
        assertThrows(IOException::class.java) {snapshotStopTail(buffer,{it.size},{stopped=true},{_,_->consumed=true})}
        assertTrue(stopped); assertFalse(consumed); assertTrue(buffer.all {it==0.toShort()})
    }
    @Test fun readFailureStopsMicWithoutClaimingAnEmptySuccessfulTail() {
        var stopped=false; var consumed=false
        assertThrows(IOException::class.java) {snapshotStopTail(ShortArray(4),{-6},{stopped=true},{_,_->consumed=true})}
        assertTrue(stopped); assertFalse(consumed)
    }
    @Test fun snapshotIsReadOnceAndConsumedOnlyAfterMicStopsThenZeroed() {
        val buffer=ShortArray(8); var reads=0; var stopped=false
        snapshotStopTail(buffer,{reads++;it.fill(3);3},{stopped=true}) {tail,count->
            assertTrue(stopped); assertEquals(3,count); assertEquals(3.toShort(),tail[2])
        }
        assertEquals(1,reads); assertTrue(buffer.all {it==0.toShort()})
    }
}
