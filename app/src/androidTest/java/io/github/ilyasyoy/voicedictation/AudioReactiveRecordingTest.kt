package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.audio.*
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.AndroidAudioWaveform
import io.github.ilyasyoy.voicedictation.ui.design.*
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual Android codecs/MIC, isolated synthetic local PCM, no provider or network. */
class AudioReactiveRecordingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun aacEosPreservesFormatDurationAndPlayableSamplesIncludingShortClips() = runBlocking {
        for (count in listOf(2400, 24000, 72000)) {
            val target = File.createTempFile("synthetic-encoder-", ".m4a", context.cacheDir)
            val driver = AndroidPcmRecordingDriver()
            try {
                driver.startEncoding(target)
                var offset = 0
                while (offset < count) {
                    val size = minOf(1024, count-offset)
                    val pcm = ShortArray(size) { (sin(2*PI*940*(offset+it)/24000)*6000).toInt().toShort() }
                    driver.write(pcm, size); offset += size
                }
                driver.stopInput(); driver.finish(); driver.close()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(target.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
                    assertEquals(24000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    val seconds = format.getLong(MediaFormat.KEY_DURATION)/1_000_000.0
                    assertTrue("duration=$seconds for $count samples", abs(seconds-count/24000.0) < .15)
                } finally { extractor.release() }
                // Decode the real AAC through the same history waveform path used in the product.
                val waveform = checkNotNull(AndroidAudioWaveform.read(target))
                assertTrue(waveform.any { it > .01f })
                assertTrue(target.length() > 0)
            } finally { runCatching { driver.close() }; target.delete() }
        }
    }
    @Test fun actualMicrophoneStopRestartCancelAndRecoveryKeepHistoryOwnership() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val store = AndroidRecordingStorage.repository(context)
        val first = AndroidAudioRecorder(context)
        val second = AndroidAudioRecorder(context)
        var saved: File? = null
        try {
            assertSame(VoiceLevels.Silence, first.levels.current())
            first.start { }
            // A competing capture must be rejected without disturbing the first.
            assertThrows(IllegalStateException::class.java) { second.start { } }
            val deadline = SystemClock.elapsedRealtime()+4000
            while (first.levels.current().measuredAtNanos == 0L && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(25)
            assertTrue("Actual AudioRecord supplies PCM (silence on emulator is valid)", first.levels.current().measuredAtNanos > 0)
            saved = first.stop()
            assertSame(VoiceLevels.Silence, first.levels.current())
            assertEquals(SavedRecordingStatus.PENDING, store.record(saved)?.status)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(saved.absolutePath)
                assertTrue(checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() > 0)
            } finally { retriever.release() }
            first.cancel() // Must not remove audio from a successful Stop.
            assertTrue(saved.exists())
            second.start { }; SystemClock.sleep(150)
            second.cancel()
            assertSame(VoiceLevels.Silence, second.levels.current())
            // Ownership and mic were released; another very short real capture can start/stop.
            first.start { }; SystemClock.sleep(150)
            val short = first.stop()
            try { assertTrue(short.length() > 0) } finally { store.discardOwned(short) }
        } finally {
            first.cancel(); second.cancel()
            saved?.let { store.discardOwned(it) }
        }
    }
    @Test fun actualBufferedMicrophoneTailSurvivesStopDuringDelayedEncoderWrite() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val target=File.createTempFile("buffered-stop-", ".m4a", context.cacheDir)
        val actual=AndroidPcmRecordingDriver()
        var run: PcmRecordingRun?=null
        val entered=java.util.concurrent.CountDownLatch(1)
        val unblock=java.util.concurrent.CountDownLatch(1)
        val normal=java.util.concurrent.atomic.AtomicInteger()
        val tail=java.util.concurrent.atomic.AtomicInteger()
        val stopFailure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        try {
            actual.start(target)
            val delayed=object : PcmRecordingDriver by actual {
                override fun read(buffer: ShortArray): Int = actual.read(buffer).also { if(it>0)normal.addAndGet(it) }
                override fun write(buffer: ShortArray, size: Int) {
                    if(entered.count>0) { entered.countDown(); check(unblock.await(3,java.util.concurrent.TimeUnit.SECONDS)) }
                    actual.write(buffer,size)
                }
                override fun stopInputAndDrain(consume:(ShortArray,Int)->Unit) {
                    actual.stopInputAndDrain { pcm,count -> tail.addAndGet(count); consume(pcm,count) }
                }
            }
            val active=PcmRecordingRun(delayed,Long.MAX_VALUE,Long.MAX_VALUE,{}, {target.delete()})
            run=active;active.start()
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            SystemClock.sleep(500) // Real AudioRecord buffers PCM while the encoder worker is held.
            val stop=Thread {try {active.stop()} catch(e:Throwable) {stopFailure.set(e)}}
            stop.start()
            val until=SystemClock.elapsedRealtime()+1000
            while(stop.state!=Thread.State.TIMED_WAITING && SystemClock.elapsedRealtime()<until) SystemClock.sleep(1)
            assertEquals(Thread.State.TIMED_WAITING,stop.state)
            unblock.countDown();stop.join(3000)
            assertFalse(stop.isAlive);assertNull(stopFailure.get())
            assertTrue("Real queued microphone PCM must be drained",tail.get()>normal.get()*2)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(target.absolutePath)
                val seconds=extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)/1_000_000.0
                assertTrue("Encoded duration must include all drained PCM",abs(seconds-(normal.get()+tail.get())/24000.0)<.15)
            } finally {extractor.release()}
            assertSame(VoiceLevels.Silence,active.current())
        } finally {
            unblock.countDown()
            run?.cancel()
            if(run==null)actual.close()
            if(run==null || run.done)target.delete()
        }
    }
    @Test fun sharedDrawableMatchesAtAnySizeAndReducedMotionIgnoresMeter() {
        // Real Android drawing: a frozen phase, same source, equal bounds -> identical pixels.
        val levels = VoiceLevelSource { VoiceLevels(.8f, FloatArray(9) { it/8f }, 1_000_000_000) }
        fun bitmap(source: VoiceLevelSource, visual: CurrentVisual, motion: Boolean = true): Bitmap {
            val result = Bitmap.createBitmap(228,228,Bitmap.Config.ARGB_8888)
            CurrentDrawable().apply { this.visual=visual; this.levels=source; setBounds(0,0,228,228); repeat(12) { advanceFrame(.033f, 1_050_000_000, motion) } }.draw(Canvas(result))
            return result
        }
        val app=bitmap(levels,CurrentVisual.RECORDING)
        val overlay=bitmap(levels,CurrentVisual.RECORDING)
        assertTrue(app.sameAs(overlay))
        val silent=bitmap(VoiceLevelSource.Silent,CurrentVisual.RECORDING)
        assertFalse("Measured sound changes real Android pixels", app.sameAs(silent))
        val reduced=bitmap(levels,CurrentVisual.RECORDING,false)
        val reducedSilent=bitmap(VoiceLevelSource.Silent,CurrentVisual.RECORDING,false)
        assertTrue("Reduced motion keeps state palette but ignores sound",reduced.sameAs(reducedSilent))
        silent.recycle(); reduced.recycle(); reducedSilent.recycle()
        // Never-started renderers/idle previews do not consume the source, regardless of stale values.
        var reads=0
        bitmap(VoiceLevelSource { reads++; error("Idle must not read microphone levels") },CurrentVisual.IDLE)
        assertEquals(0,reads)
        app.recycle(); overlay.recycle()
    }
}
