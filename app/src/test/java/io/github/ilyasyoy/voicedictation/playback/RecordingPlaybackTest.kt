package io.github.ilyasyoy.voicedictation.playback

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecordingPlaybackTest {
    private class Driver: PlaybackDriver {
        lateinit var ready: () -> Unit
        lateinit var ended: () -> Unit
        lateinit var failed: () -> Unit
        var released = false
        var plays = 0
        var pauses = 0
        override fun prepare(file: File, ready: () -> Unit, ended: () -> Unit, failed: () -> Unit) {
            this.ready=ready; this.ended=ended; this.failed=failed
        }
        override fun play() { check(!released); plays++ }
        override fun pause() { pauses++ }
        override fun release() { released=true }
        override val position = 800
        override val duration = 2000
    }
    private fun audio() = File.createTempFile("playback-", ".m4a").apply {writeText("synthetic")}
    @Test fun switchingSelectionReleasesOldPlayerAndRejectsItsLateCallbacks() {
        val players=mutableListOf<Driver>()
        val owner=RecordingPlayback {Driver().also(players::add)}
        val file=audio()
        try {
            owner.toggle("first",file);owner.toggle("second",file)
            assertTrue(players[0].released)
            players[0].ready();players[0].failed();players[0].ended()
            assertEquals("second",owner.state.value.id);assertEquals(0,players[0].plays)
            players[1].ready();assertTrue(owner.state.value.playing)
        } finally {owner.stop();file.delete()}
    }
    @Test fun closingWhilePreparingCannotRestartAudio() {
        val player=Driver();val owner=RecordingPlayback {player};val file=audio()
        try {owner.toggle("one",file);owner.stop();player.ready();assertTrue(player.released);assertNull(owner.state.value.id);assertEquals(0,player.plays)}
        finally {file.delete()}
    }
    @Test fun pauseResumeProgressAndCompletionKeepOnePlayer() {
        val player=Driver();val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);player.ready();owner.tick()
            assertEquals(800,owner.state.value.position)
            owner.toggle("one",file);assertFalse(owner.state.value.playing);assertEquals(1,player.pauses)
            owner.toggle("one",file);assertEquals(2,player.plays)
            player.ended();assertTrue(player.released);assertNull(owner.state.value.id)
        } finally {owner.stop();file.delete()}
    }
    @Test fun missingAndDecoderFailureAreVisibleWithoutDeletingAudio() {
        val player=Driver();val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("bad",file);player.failed()
            assertEquals("bad",owner.state.value.failedId);assertTrue(file.exists());assertTrue(player.released)
            owner.toggle("missing",File(file.parentFile,"absent"))
            assertEquals("missing",owner.state.value.failedId)
        } finally {owner.stop();file.delete()}
    }
}
