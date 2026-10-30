package io.github.ilyasyoy.voicedictation.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Non-blocking driver port. A run owns its driver until its worker's finally releases it. */
internal interface PcmCapture {
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
    fun release()
}

internal class PcmCaptureWorker(private val create: () -> PcmCapture) : PcmSource {
    private class Run(val capture: PcmCapture) {
        val running = AtomicBoolean(true)
        lateinit var worker: Thread
    }
    private val lock = Any()
    private var active: Run? = null
    override fun start(onAudio: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        synchronized(lock) {
            check(active == null)
            val capture = create()
            try { capture.start() }
            catch (e: Exception) { runCatching { capture.release() }; throw e }
            val run = Run(capture)
            run.worker = Thread({
                try {
                    val buffer = ByteArray(4800)
                    while (run.running.get()) {
                        val count = capture.read(buffer)
                        if (!run.running.get()) break
                        when {
                            count > 0 && count <= buffer.size -> onAudio(buffer.copyOf(count))
                            count == 0 -> Thread.sleep(10)
                            else -> throw IOException("Audio capture stopped")
                        }
                    }
                } catch (e: Exception) { if (run.running.getAndSet(false)) onError(e) }
                finally {
                    run.running.set(false)
                    runCatching { capture.stop() }
                    runCatching { capture.release() }
                    synchronized(lock) { if (active === run) active = null }
                }
            }, "dictation-pcm").apply { isDaemon = true }
            active = run
            try { run.worker.start() }
            catch (e: Exception) {
                active = null; run.running.set(false)
                runCatching { capture.stop() }; runCatching { capture.release() }; throw e
            }
        }
    }
    override fun stop() {
        val run = synchronized(lock) {
            active?.also { it.running.set(false); active = null }
        }
        // No main-thread join or premature release; each old run has its own cancellation flag.
        run?.worker?.interrupt()
    }
}
