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
        val seeks = mutableListOf<Int>()
        var deferSeek = false
        var seekDone: (() -> Unit)? = null
        var decodedDuration = 2000
        var failSeek = false
        override fun prepare(file: File, ready: () -> Unit, ended: () -> Unit, failed: () -> Unit) {
            this.ready=ready; this.ended=ended; this.failed=failed
        }
        override fun play() { check(!released); plays++ }
        override fun pause() { check(plays > 0) { "Cannot pause before the first start" }; pauses++ }
        override fun seekTo(position: Int, completed: () -> Unit) {
            check(!failSeek)
            check(!released); seeks += position
            if (deferSeek) seekDone = completed else completed()
        }
        override fun release() { released=true }
        override val position = 800
        override val duration get() = decodedDuration
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
    @Test fun seekingAnotherRecordingPreparesPausedUsingDecodedDuration() {
        val players=mutableListOf<Driver>();val owner=RecordingPlayback {Driver().also(players::add)};val file=audio()
        try {
            owner.toggle("first",file);players[0].ready()
            owner.seek("second",file,.75f)
            assertTrue(players[0].released);assertTrue(owner.state.value.loading)
            assertEquals(.75f,owner.state.value.fraction,0f)
            owner.seek("second",file,.25f);players[1].ready()
            assertEquals(listOf(500),players[1].seeks);assertEquals(500,owner.state.value.position)
            assertFalse(owner.state.value.playing);assertEquals(0,players[1].plays)
        } finally {owner.stop();file.delete()}
    }
    @Test fun seekPreservesPlayingOrPausedIntentAndClampsEndpoints() {
        val player=Driver();val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);player.ready();owner.seek("one",file,.5f)
            assertEquals(1000,owner.state.value.position);assertTrue(owner.state.value.playing)
            owner.toggle("one",file);owner.seek("one",file,-2f)
            assertEquals(0,owner.state.value.position);assertFalse(owner.state.value.playing)
            val plays=player.plays;owner.seek("one",file,2f)
            assertEquals(2000,owner.state.value.position);assertFalse(owner.state.value.playing)
            assertEquals(plays,player.plays)
            owner.toggle("one",file)
            assertEquals(0,owner.state.value.position);assertTrue(owner.state.value.playing)
            assertEquals(listOf(1000,0,2000,0),player.seeks)
        } finally {owner.stop();file.delete()}
    }
    @Test fun rapidSeeksCoalesceAndTicksCannotOverwritePendingPosition() {
        val player=Driver().apply {deferSeek=true};val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);player.ready();owner.seek("one",file,.2f)
            owner.seek("one",file,.4f);owner.seek("one",file,.8f);owner.tick()
            assertEquals(listOf(400),player.seeks);assertEquals(1600,owner.state.value.position)
            checkNotNull(player.seekDone).invoke();assertEquals(listOf(400,1600),player.seeks)
            owner.toggle("one",file) // A pause during an asynchronous seek must not be undone.
            val plays=player.plays;checkNotNull(player.seekDone).invoke()
            assertFalse(owner.state.value.seeking);assertFalse(owner.state.value.playing);assertEquals(plays,player.plays)
        } finally {owner.stop();file.delete()}
    }
    @Test fun lateSeekCallbackCannotResumeAReleasedOwner() {
        val players=mutableListOf<Driver>();val owner=RecordingPlayback {Driver().apply {deferSeek=true}.also(players::add)};val file=audio()
        try {
            owner.toggle("first",file);players[0].ready();owner.seek("first",file,.7f)
            val late=checkNotNull(players[0].seekDone)
            owner.toggle("second",file);players[1].ready();late()
            assertEquals("second",owner.state.value.id);assertEquals(1,players[0].plays)
            owner.seek("second",file,Float.NaN);assertTrue(players[1].seeks.isEmpty())
        } finally {owner.stop();file.delete()}
    }
    @Test fun seekingToEndPausesAndResumeWaitsForRewind() {
        val player=Driver().apply {deferSeek=true};val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);player.ready();owner.seek("one",file,1f)
            assertFalse(owner.state.value.playing);assertEquals(1,player.pauses)
            checkNotNull(player.seekDone).invoke();val plays=player.plays
            player.ended();assertEquals("one",owner.state.value.id) // A paused end seek is not natural completion.
            owner.toggle("one",file);assertEquals(plays,player.plays)
            checkNotNull(player.seekDone).invoke();assertTrue(owner.state.value.playing)
            assertEquals(plays+1,player.plays);assertEquals(listOf(2000,0),player.seeks)
        } finally {owner.stop();file.delete()}
    }
    @Test fun subMillisecondBoundsAndUnknownDurationNeverDivideByZero() {
        val player=Driver().apply {decodedDuration=1};val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.seek("one",file,1f);player.ready();assertEquals(1,owner.state.value.position)
            owner.seek("one",file,0f);assertEquals(0,owner.state.value.position)
            player.decodedDuration=0;owner.seek("one",file,.5f)
            assertEquals(0,owner.state.value.position);assertFalse(owner.state.value.playing)
            assertEquals(0f,owner.state.value.fraction,0f)
        } finally {owner.stop();file.delete()}
    }
    @Test fun seekFailureReleasesDecoderAndRetainsTheRecording() {
        val player=Driver();val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);player.ready();player.failSeek=true;owner.seek("one",file,.5f)
            assertEquals("one",owner.state.value.failedId);assertTrue(player.released);assertTrue(file.exists())
        } finally {owner.stop();file.delete()}
    }
    @Test fun playThenSeekEndDuringLoadingDoesNotPauseAnUnstartedPlayer() {
        val player=Driver();val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);owner.seek("one",file,1f);player.ready()
            assertNull(owner.state.value.failedId);assertEquals("one",owner.state.value.id)
            assertFalse(owner.state.value.loading);assertFalse(owner.state.value.playing)
            assertEquals(2000,owner.state.value.position);assertEquals(0,player.pauses);assertEquals(0,player.plays)
            owner.toggle("one",file);assertTrue(owner.state.value.playing);assertEquals(1,player.plays)
        } finally {owner.stop();file.delete()}
    }
    @Test fun pauseDuringTheInitialSeekOnlyChangesIntentUntilPlaybackStarts() {
        val player=Driver().apply {deferSeek=true};val owner=RecordingPlayback {player};val file=audio()
        try {
            owner.toggle("one",file);owner.seek("one",file,.5f);player.ready()
            assertTrue(owner.state.value.seeking);assertTrue(owner.state.value.playing)
            owner.toggle("one",file)
            assertNull(owner.state.value.failedId);assertEquals(0,player.pauses);assertFalse(owner.state.value.playing)
            checkNotNull(player.seekDone).invoke()
            assertFalse(owner.state.value.seeking);assertEquals(0,player.plays)
            owner.toggle("one",file);assertTrue(owner.state.value.playing);assertEquals(1,player.plays)
        } finally {owner.stop();file.delete()}
    }
}
