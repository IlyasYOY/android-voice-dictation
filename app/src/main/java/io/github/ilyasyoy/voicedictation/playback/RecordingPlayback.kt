package io.github.ilyasyoy.voicedictation.playback

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface PlaybackDriver {
    fun prepare(file: File, ready: () -> Unit, ended: () -> Unit, failed: () -> Unit)
    fun play()
    fun pause()
    fun seekTo(position: Int, completed: () -> Unit)
    fun release()
    val position: Int
    val duration: Int
}
internal data class PlaybackState(val id: String? = null, val loading: Boolean = false,
    val playing: Boolean = false, val position: Int = 0, val duration: Int = 0, val failedId: String? = null,
    val seeking: Boolean = false, val requestedFraction: Float? = null) {
    val fraction get() = requestedFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** Serial owner; callbacks from a released player cannot replace a newer selection. */
internal class RecordingPlayback(private val factory: () -> PlaybackDriver) {
    private var generation = 0L
    private var driver: PlaybackDriver? = null
    private var desiredSeek: Int? = null
    private var inFlightSeek: Int? = null
    private var playOnReady = false
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    fun toggle(id: String, file: File) {
        val current = mutable.value
        if (current.id == id && current.loading) { playOnReady = true; return }
        if (current.id == id && !current.loading) {
            try {
                if (current.playing) {
                    driver?.pause(); mutable.value = current.copy(playing = false)
                } else {
                    mutable.value = current.copy(playing = true)
                    if (current.duration > 0 && current.position >= current.duration) seekPrepared(0f)
                    else if (!current.seeking) driver?.play()
                }
            } catch (_: Exception) { fail(id) }
            return
        }
        open(id, file, autoplay = true)
    }
    /** A new selection is prepared on pause; an existing selection keeps its transport intent. */
    fun seek(id: String, file: File, fraction: Float) {
        if (!fraction.isFinite()) return
        val target = fraction.coerceIn(0f, 1f)
        if (mutable.value.id != id) { open(id, file, autoplay = false, fraction = target); return }
        if (mutable.value.loading) mutable.value = mutable.value.copy(requestedFraction = target)
        else try { seekPrepared(target) } catch (_: Exception) { fail(id) }
    }
    private fun open(id: String, file: File, autoplay: Boolean, fraction: Float? = null) {
        stop()
        if (!file.isFile || file.length() == 0L) { fail(id); return }
        val token = generation
        playOnReady = autoplay
        mutable.value = PlaybackState(id, loading = true, requestedFraction = fraction)
        try {
            val player = factory(); driver = player
            player.prepare(file, ready = {
                if (generation == token && driver === player) try {
                    val target = mutable.value.requestedFraction
                    mutable.value = mutable.value.copy(loading = false, playing = playOnReady, duration = player.duration.coerceAtLeast(0))
                    if (target != null) seekPrepared(target) else if (playOnReady) player.play()
                } catch (_: Exception) { fail(id) }
            }, ended = { if (generation == token && mutable.value.playing && !mutable.value.seeking) stop() },
                failed = { if (generation == token) fail(id) })
        } catch (_: Exception) { fail(id) }
    }
    private fun seekPrepared(fraction: Float) {
        val player = driver ?: return
        val current = mutable.value
        val duration = player.duration.coerceAtLeast(0)
        val target = (fraction.toDouble() * duration).toInt().coerceIn(0, duration)
        val atEnd = target == duration
        if (atEnd && current.playing) player.pause()
        desiredSeek = target
        mutable.value = current.copy(position = target, duration = duration, seeking = true,
            requestedFraction = fraction, playing = current.playing && !atEnd)
        if (inFlightSeek == null) dispatchSeek(player)
    }
    /** MediaPlayer seeks are asynchronous. Coalesce repeated requests without confusing their callbacks. */
    private fun dispatchSeek(player: PlaybackDriver) {
        val target = desiredSeek ?: return
        val token = generation
        inFlightSeek = target
        player.seekTo(target) {
            if (generation == token && driver === player) try {
                inFlightSeek = null
                if (desiredSeek != target) dispatchSeek(player)
                else {
                    desiredSeek = null
                    mutable.value = mutable.value.copy(seeking = false, requestedFraction = null)
                    if (mutable.value.playing) player.play()
                }
            } catch (_: Exception) { fail(mutable.value.id ?: return@seekTo) }
        }
    }
    fun tick() {
        val player = driver ?: return
        if (mutable.value.loading || mutable.value.seeking) return
        try { mutable.value = mutable.value.copy(position = player.position, duration = player.duration) }
        catch (_: Exception) { fail(mutable.value.id ?: return) }
    }
    fun stop() {
        generation++
        val old = driver; driver = null
        desiredSeek = null; inFlightSeek = null; playOnReady = false
        mutable.value = PlaybackState()
        runCatching { old?.release() }
    }
    private fun fail(id: String) { stop(); mutable.value = PlaybackState(failedId = id) }
}
