package io.github.ilyasyoy.voicedictation.playback

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface PlaybackDriver {
    fun prepare(file: File, ready: () -> Unit, ended: () -> Unit, failed: () -> Unit)
    fun play()
    fun pause()
    fun release()
    val position: Int
    val duration: Int
}
internal data class PlaybackState(val id: String? = null, val loading: Boolean = false,
    val playing: Boolean = false, val position: Int = 0, val duration: Int = 0, val failedId: String? = null)

/** Serial owner; callbacks from a released player cannot replace a newer selection. */
internal class RecordingPlayback(private val factory: () -> PlaybackDriver) {
    private var generation = 0L
    private var driver: PlaybackDriver? = null
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    fun toggle(id: String, file: File) {
        val current = mutable.value
        if (current.id == id && !current.loading) {
            try {
                if (current.playing) driver?.pause() else driver?.play()
                mutable.value = current.copy(playing = !current.playing)
            } catch (_: Exception) { fail(id) }
            return
        }
        stop()
        if (!file.isFile || file.length() == 0L) { fail(id); return }
        val token = generation
        mutable.value = PlaybackState(id, loading = true)
        try {
            val player = factory(); driver = player
            player.prepare(file, ready = {
                if (generation == token && driver === player) try {
                    player.play()
                    mutable.value = PlaybackState(id, playing = true, duration = player.duration)
                } catch (_: Exception) { fail(id) }
            }, ended = { if (generation == token) stop() },
                failed = { if (generation == token) fail(id) })
        } catch (_: Exception) { fail(id) }
    }
    fun tick() {
        val player = driver ?: return
        if (mutable.value.loading) return
        try { mutable.value = mutable.value.copy(position = player.position, duration = player.duration) }
        catch (_: Exception) { fail(mutable.value.id ?: return) }
    }
    fun stop() {
        generation++
        val old = driver; driver = null
        mutable.value = PlaybackState()
        runCatching { old?.release() }
    }
    private fun fail(id: String) { stop(); mutable.value = PlaybackState(failedId = id) }
}
