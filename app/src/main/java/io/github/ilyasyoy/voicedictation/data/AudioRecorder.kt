package io.github.ilyasyoy.voicedictation.data

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import io.github.ilyasyoy.voicedictation.audio.*
import io.github.ilyasyoy.voicedictation.domain.AudioPolicy
import io.github.ilyasyoy.voicedictation.session.RecordingStorageFailure

interface AudioRecorder {
    val levels: VoiceLevelSource get() = VoiceLevelSource.Silent
    fun start(onLimit: () -> Unit)
    fun stop(): File
    fun cancel()
}

/** Explicit Activity/foreground-service capture. One process-wide microphone owner, no idle resource. */
class AndroidAudioRecorder(context: Context) : AudioRecorder {
    private val context = context.applicationContext
    private val files get() = AndroidRecordingStorage.repository(context)
    @Volatile private var run: PcmRecordingRun? = null
    private var file: File? = null
    private var recovery: StoppedRecordingRecovery? = null
    override val levels = VoiceLevelSource { run?.current() ?: VoiceLevels.Silence }
    override fun start(onLimit: () -> Unit) {
        check(run == null || run?.done == true)
        check(microphoneOwned.compareAndSet(false, true)) { "Microphone already owned" }
        var driver: AndroidPcmRecordingDriver? = null
        try {
            val target = try { files.allocate() } catch (e: HistoryCapacityReached) { throw e }
                catch (e: Exception) { throw RecordingStorageFailure(e) }
            file = target
            io.github.ilyasyoy.voicedictation.playback.AndroidRecordingPlayback.interrupt()
            val completion = StoppedRecordingRecovery { files.retain(target, SavedRecordingFailure.STORAGE) }
            recovery = completion
            driver = AndroidPcmRecordingDriver().also { it.start(target) }
            run = PcmRecordingRun(driver, AudioPolicy.MAX_SECONDS.toLong() * AudioPolicy.SAMPLE_RATE,
                // Leave ample room for MP4 headers/indexes, below the unchanged file/upload limits.
                AudioPolicy.FILE_BYTES - 131_072, onLimit, discard = { files.discardOwned(target) },
                released = { try { completion.afterRelease() } finally { microphoneOwned.set(false) } }).also { it.start() }
        } catch (e: Exception) {
            runCatching { driver?.close() }
            microphoneOwned.set(false); run = null
            file?.let { runCatching { files.discardOwned(it) } }; file = null
            throw e
        }
    }
    override fun stop(): File {
        val active = checkNotNull(run)
        val target = checkNotNull(file)
        try {
            active.stop()
            require(target.length() > 0)
            try { files.finalized(target) } catch (e: Exception) { throw RecordingStorageFailure(e) }
            return target
        } catch (e: Exception) {
            // Keep the file even if MP4 finalization or a device driver fails. Never delete a live writer.
            runCatching { recovery?.request() }
            throw RecordingStorageFailure(e)
        } finally {
            // Subsequent lifecycle cancel must not turn a failed Stop into destructive Cancel.
            // The worker keeps its lease/reservation until cleanup, then transfers recovery itself.
            run = null; recovery = null; file = null
        }
    }
    override fun cancel() {
        val active = run
        try { active?.cancel() }
        finally {
            if (active == null || active.done) run = null
            file = null // The worker discards only its own file, after releasing all platform resources.
        }
    }
    private companion object { val microphoneOwned = AtomicBoolean() }
}
