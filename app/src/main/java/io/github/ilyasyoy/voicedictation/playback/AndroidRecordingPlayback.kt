package io.github.ilyasyoy.voicedictation.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/** Process arbiter is also interrupted immediately before every microphone start. */
internal object AndroidRecordingPlayback {
    private var owner: RecordingPlayback? = null
    fun create(context: Context): RecordingPlayback {
        val app = context.applicationContext
        return RecordingPlayback { AndroidPlaybackDriver(app) { interrupt() } }
    }
    fun claim(playback: RecordingPlayback) {
        if (owner !== playback) interrupt()
        owner = playback
    }
    fun interrupt() { owner?.stop(); owner = null }
    fun release(playback: RecordingPlayback) {
        playback.stop()
        if (owner === playback) owner = null
    }
}
private class AndroidPlaybackDriver(context: Context, private val lost: () -> Unit): PlaybackDriver {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0 && focused) lost() }.build()
    private var focused = false
    private val player = MediaPlayer().apply { setAudioAttributes(attributes) }
    override fun prepare(file: File, ready: () -> Unit, ended: () -> Unit, failed: () -> Unit) {
        player.setOnPreparedListener { ready() }
        player.setOnCompletionListener { ended() }
        player.setOnErrorListener { _, _, _ -> failed(); true }
        player.setDataSource(file.absolutePath); player.prepareAsync()
    }
    override fun play() {
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focused = true; player.start()
    }
    override fun pause() { player.pause(); abandon() }
    override fun seekTo(position: Int, completed: () -> Unit) {
        player.setOnSeekCompleteListener { completed() }
        player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
    }
    private fun abandon() { if (focused) { focused = false; audio.abandonAudioFocusRequest(focus) } }
    override fun release() {
        try { player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null); player.setOnSeekCompleteListener(null); player.release() }
        finally { abandon() }
    }
    override val position get() = player.currentPosition
    override val duration get() = player.duration
}
