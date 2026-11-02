package io.github.ilyasyoy.voicedictation.audio

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single driver owns microphone + encoder. All reads, encoding and cleanup stay on its worker. */
internal interface PcmRecordingDriver : AutoCloseable {
    fun read(buffer: ShortArray): Int
    fun write(buffer: ShortArray, size: Int)
    fun stopInput()
    /** Stop MIC before invoking consume, with at most one bounded snapshot of queued PCM. */
    fun stopInputAndDrain(consume: (ShortArray, Int) -> Unit) { stopInput() }
    fun finish()
    val cadence: CaptureReadCadence? get() = null
    val bytesWritten: Long
}

/** No queue of PCM: encode each read before the next read, analyze the same ephemeral buffer. */
internal class PcmRecordingRun(
    private val driver: PcmRecordingDriver,
    private val maxSamples: Long,
    private val maxBytes: Long,
    private val onLimit: () -> Unit,
    private val discard: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val released: () -> Unit = {},
) : VoiceLevelSource {
    private val stopping = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val complete = CountDownLatch(1)
    private val level = AtomicReference(VoiceLevels.Silence)
    private val publicationLock = Any()
    @Volatile private var failure: Exception? = null
    val done get() = complete.count == 0L
    override fun current(): VoiceLevels = level.get()
    private val worker = Thread(::capture, "voice-file-capture")
    fun start() = worker.start()
    private fun silence() = synchronized(publicationLock) { level.set(VoiceLevels.Silence) }
    private fun capture() {
        val pcm = ShortArray(1024)
        val spectrum = VoiceSpectrum()
        val cadence = driver.cadence
        var samples = 0L
        var limit = false
        var lastAudio = nanoTime()
        try {
            while (!stopping.get()) {
                val read = driver.read(pcm)
                if (read < 0) throw IOException("Microphone read failed")
                require(read <= pcm.size)
                cadence?.read(read, nanoTime())
                if (read == 0) {
                    if (nanoTime() - lastAudio > 2_000_000_000L) throw IOException("Microphone stalled")
                    Thread.sleep(8)
                    continue
                }
                lastAudio = nanoTime()
                val accepted = minOf(read.toLong(), maxSamples - samples).toInt()
                driver.write(pcm, accepted) // Capture reliability takes priority over visual analysis.
                samples += accepted
                // Native capture headroom wins over optional FFT. No extra PCM queue is added.
                if (cadence?.mayAnalyze(nanoTime()) != false) {
                    // A broken visualizer cannot terminate the recording.
                    runCatching { spectrum.accept(pcm, accepted, nanoTime()) { frame ->
                        synchronized(publicationLock) { if (!stopping.get()) level.set(frame) }
                    } }.onFailure { silence() }
                } else { spectrum.reset(); silence() }
                if (samples >= maxSamples || driver.bytesWritten >= maxBytes) { limit = true; break }
            }
            if (cancelled.get()) driver.stopInput()
            else {
                val tailDeadline = nanoTime() + 1_000_000_000L
                driver.stopInputAndDrain { tail, count ->
                    require(count in 0..tail.size)
                    var offset = 0
                    while (offset < count && samples < maxSamples && !cancelled.get()) {
                        if (nanoTime() > tailDeadline) throw IOException("Stop tail encoding timed out")
                        val size = minOf(pcm.size.toLong(), (count - offset).toLong(), maxSamples - samples).toInt()
                        tail.copyInto(pcm, 0, offset, offset + size)
                        driver.write(pcm, size) // MIC is already stopped; never capture while draining encoder.
                        offset += size; samples += size
                    }
                    if (!cancelled.get() && nanoTime() > tailDeadline) throw IOException("Stop tail encoding timed out")
                }
            }
            if (!cancelled.get()) driver.finish()
        } catch (e: Exception) { failure = e; limit = !stopping.get() }
        finally {
            stopping.set(true); silence(); spectrum.reset(); pcm.fill(0)
            try { driver.close() } catch (e: Exception) { if (failure == null) failure = e }
            // Cancellation is serialized with this decision, including a cancel arriving during close.
            runCatching(released)
            synchronized(cancelled) {
                if (cancelled.get()) runCatching(discard).onFailure { if (failure == null && it is Exception) failure = it }
                complete.countDown()
            }
        }
        if (limit && !cancelled.get()) runCatching(onLimit)
    }
    /** Bounded like the existing synchronous file recorder port; never steals worker resources. */
    fun stop(timeoutMillis: Long = 2000) {
        stopping.set(true); silence()
        if (!complete.await(timeoutMillis, TimeUnit.MILLISECONDS)) throw IOException("Recorder cleanup timed out")
        failure?.let { throw it }
    }
    fun cancel(timeoutMillis: Long = 2000) {
        synchronized(cancelled) {
            cancelled.set(true)
            if (done) discard()
        }
        stopping.set(true); silence()
        complete.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
