package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingRecordingRecoveryTest {
    private fun audio()=File.createTempFile("retry-owned-", ".m4a").apply {writeText("synthetic fixture")}
    @Test fun failedSessionTransfersOnlyAudioAndNewRecordingDoesNotEraseIt()=runTest {
        val file=audio(); val other=audio(); val recovery=FloatingRecordingRecovery()
        val session=DictationSession(backgroundScope,object: AudioRecorder {
            override fun start(onLimit:()->Unit)=Unit
            override fun stop()=file
            override fun cancel()=Unit
        },Transcriber {_,_->throw IOException("offline")})
        try {
            session.setKey("synthetic-key");session.start(true);session.stop();runCurrent()
            assertTrue(session.state.value.canRetry)
            recovery.retain(checkNotNull(session.takeRetryRecording()));session.close()
            assertEquals("",session.state.value.key);assertTrue(file.exists())
            recovery.retain(other) // Another failed Stop must not overwrite the previous owner.
            assertEquals(2,recovery.count)
            assertEquals("Recovered",recovery.retry {owned->assertEquals(file,owned);"Recovered"})
            assertFalse(file.exists());assertTrue(other.exists());assertEquals(1,recovery.count)
            recovery.discard();assertFalse(other.exists());assertEquals(0,recovery.count)
        } finally {session.close();file.delete();other.delete()}
    }
    @Test fun authOrNetworkRetryFailureKeepsAudioAndNeverRetriesItself()=runTest {
        val file=audio();val recovery=FloatingRecordingRecovery();recovery.retain(file);var calls=0
        try {
            for(failure in listOf(IOException("offline"),ApiFailure(401))) {
                try {recovery.retry {calls++;throw failure};fail("failure expected")}catch(_:IOException){}
                assertTrue(file.exists());assertEquals(1,recovery.count);assertFalse(recovery.retrying)
            }
            runCurrent();assertEquals(2,calls)
            assertEquals("Explicit retry",recovery.retry {calls++;"Explicit retry"})
            assertEquals(3,calls);assertFalse(file.exists());assertEquals(0,recovery.count)
        } finally {recovery.discard();file.delete()}
    }
    @Test fun cancelledRetryRestoresAudioWithoutHoldingNetworkOrKeyOwner()=runTest {
        val file=audio();val recovery=FloatingRecordingRecovery();recovery.retain(file)
        val response=CompletableDeferred<String>()
        try {
            val retry=launch {recovery.retry {response.await()}}
            runCurrent();assertTrue(recovery.retrying)
            retry.cancelAndJoin()
            assertFalse(recovery.retrying);assertEquals(1,recovery.count);assertTrue(file.exists())
            recovery.discard();assertFalse(file.exists())
        } finally {file.delete()}
    }
    @Test fun secondExplicitRetryCannotDuplicateAnInFlightUpload()=runTest {
        val file=audio();val recovery=FloatingRecordingRecovery();recovery.retain(file)
        val response=CompletableDeferred<String>();var calls=0
        try {
            val retry=async {recovery.retry {calls++;response.await()}}
            runCurrent();assertNull(recovery.retry {calls++;"duplicate"});assertEquals(1,calls)
            recovery.discard();assertTrue(file.exists()) // Discard UI is disabled while a request owns it.
            response.complete("Done");assertEquals("Done",retry.await())
            assertFalse(file.exists());assertEquals(0,recovery.count)
        } finally {file.delete()}
    }
}
