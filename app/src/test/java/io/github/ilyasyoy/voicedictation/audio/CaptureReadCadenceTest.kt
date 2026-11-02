package io.github.ilyasyoy.voicedictation.audio

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class CaptureReadCadenceTest {
    @Test fun single500msEncoderStallFitsNativeBudgetAndSkipsOptionalAnalysisUntilCaughtUp() {
        val cadence = CaptureReadCadence(48_000, 24_000, 0)
        cadence.read(1024, 42_666_667)
        assertTrue(cadence.mayAnalyze(42_666_667))
        assertFalse(cadence.mayAnalyze(542_666_667))
        repeat(12) { cadence.read(1024, 542_666_667) }
        assertTrue(cadence.mayAnalyze(542_666_667))
    }
    @Test fun sustainedSlowEncoderCannotSilentlyAccumulateAnUnboundedBacklog() {
        val clock = AtomicLong()
        val callback = CountDownLatch(1)
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val driver = object : PcmRecordingDriver {
            override val cadence = CaptureReadCadence(48_000, 24_000, 0)
            override val bytesWritten = 0L
            override fun read(buffer: ShortArray) = buffer.size
            override fun write(buffer: ShortArray, size: Int) { actions.add("write"); clock.addAndGet(500_000_000) }
            override fun stopInput() { actions.add("stopInput") }
            override fun finish() { actions.add("finish") }
            override fun close() { actions.add("close") }
        }
        val run = PcmRecordingRun(driver, Long.MAX_VALUE, Long.MAX_VALUE, { callback.countDown() },
            { fail("A slow encoder must retain its interrupted clip, not cancel/delete") }, nanoTime=clock::get)
        run.start()
        assertTrue(callback.await(2,TimeUnit.SECONDS))
        val failure=assertThrows(IOException::class.java) { run.stop() }
        assertTrue(checkNotNull(failure.message).contains("backlog"))
        assertEquals(4,actions.count { it=="write" })
        // Estimated unread input remains below native 2-second capacity when the guard trips.
        assertTrue(clock.get() - 4*1024L*1_000_000_000/24_000 < 2_000_000_000)
        assertEquals("close",actions.last())
        assertFalse(actions.contains("finish"))
        assertSame(VoiceLevels.Silence,run.current())
    }
    @Test fun emptyReadsRebaseClockDriftDuringLongRecordings() {
        val cadence=CaptureReadCadence(48_000,24_000,0)
        for (second in 0..1800) {
            val now=second*1_000_200_000L // deliberate 200 ppm skew
            cadence.read(0,now)
            cadence.read(1024,now+42_666_667)
            assertTrue(cadence.mayAnalyze(now+42_666_667))
        }
    }
}
