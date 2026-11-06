package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingScreenOnTest {
    private val editor=EditorToken("fixture.editor",1,"body",1,7,7,false,false,0x20001,0,-1,-1)
    private inner class Fixture(scope: CoroutineScope, screen: (Boolean)->Unit,
        key: ApiKeyStore=NoKeyStore, failStart: Boolean=false, failUpload: Boolean=false,
        valid: Boolean=true) : AutoCloseable {
        val answer=CompletableDeferred<String>()
        val file=File.createTempFile("screen-lease-",".m4a").apply {writeText("synthetic")}
        var cancelled=0
        val session=DictationSession(scope,object:AudioRecorder {
            override fun start(onLimit:()->Unit) {if(failStart)throw IOException("synthetic start failure")}
            override fun stop()=file
            override fun cancel() {cancelled++}
        },Transcriber {_,_->if(failUpload)throw IOException("synthetic network failure") else answer.await()},keyStore=key)
        val capture: FloatingCapture=FloatingCapture(scope,session,editor,{FloatingEnvironment(editor,valid,true,true,true,true)},{},
            {state->if(state.phase==Phase.ERROR || state.phase==Phase.RESULT)assertFalse(capture.keepsScreenOn)},screen)
        init {if(key===NoKeyStore)session.setKey("synthetic")}
        override fun close() {capture.close();file.delete()}
    }
    @Test fun leaseCoversStartupRecordingKeyWaitAndTranscriptionWithoutGap()=runTest {
        val events=mutableListOf<Boolean>();val key=CompletableDeferred<String?>()
        val store=object:ApiKeyStore {
            override suspend fun load()=key.await()
            override suspend fun save(key:String)=Unit
            override suspend fun forget()=Unit
        }
        Fixture(backgroundScope,events::add,key=store).use { f->
            f.capture.start();runCurrent()
            assertEquals(Phase.RECORDING,f.session.state.value.phase)
            assertEquals(listOf(true),events)
            f.session.stop();runCurrent()
            assertEquals(Phase.TRANSCRIBING,f.session.state.value.phase)
            assertTrue(f.session.state.value.keyBusy);assertEquals(listOf(true),events)
            key.complete("synthetic");runCurrent();assertEquals(listOf(true),events)
            f.answer.complete("synthetic result");runCurrent()
            assertEquals(Phase.RESULT,f.session.state.value.phase)
            assertEquals(listOf(true,false),events)
        }
        assertEquals(listOf(true,false),events)
    }
    @Test fun cancelReleasesDuringRecordingAndDuringTranscription()=runTest {
        for(processing in listOf(false,true)) {
            val events=mutableListOf<Boolean>()
            Fixture(backgroundScope,events::add).use {f->
                f.capture.start();runCurrent()
                if(processing){f.session.stop();runCurrent()}
                f.session.cancel();runCurrent()
                assertFalse(f.capture.keepsScreenOn);assertEquals(listOf(true,false),events)
                f.answer.complete("late result");runCurrent();assertEquals(listOf(true,false),events)
            }
        }
    }
    @Test fun startAndUploadFailuresReleaseAndInvalidStartNeverAcquires()=runTest {
        for(startFailure in listOf(false,true)) {
            val events=mutableListOf<Boolean>()
            Fixture(backgroundScope,events::add,failStart=startFailure,failUpload=!startFailure).use {f->
                f.capture.start();runCurrent()
                if(!startFailure){f.session.stop();runCurrent()}
                assertEquals(Phase.ERROR,f.session.state.value.phase)
                assertEquals(listOf(true,false),events);assertFalse(f.capture.keepsScreenOn)
            }
        }
        val events=mutableListOf<Boolean>()
        Fixture(backgroundScope,events::add,valid=false).use {f->f.capture.start();runCurrent();assertFalse(f.capture.keepsScreenOn)}
        assertTrue(events.isEmpty())
    }
    @Test fun closeIsIdempotentAndLateOldCompletionCannotClearNextRecording()=runTest {
        var screen=false;val events=mutableListOf<Boolean>()
        val apply:(Boolean)->Unit={screen=it;events+=it}
        Fixture(backgroundScope,apply).use {old->
            old.capture.start();runCurrent();old.session.stop();runCurrent()
            old.capture.close();old.capture.close();assertFalse(screen)
            Fixture(backgroundScope,apply).use {next->
                next.capture.start();runCurrent();assertTrue(screen)
                old.answer.complete("late old result");runCurrent();assertTrue(screen)
                next.capture.close();assertFalse(screen)
            }
        }
        assertEquals(listOf(true,false,true,false),events)
    }
    @Test fun closeStillReleasesRecorderIfScreenCallbackFails()=runTest {
        val f=Fixture(backgroundScope,{if(!it)throw IllegalStateException("synthetic window failure")})
        try {
            f.capture.start();runCurrent();assertTrue(f.capture.keepsScreenOn)
            assertThrows(IllegalStateException::class.java){f.capture.close()}
            assertFalse(f.capture.keepsScreenOn);assertTrue(f.cancelled>0)
            f.capture.close()
        } finally {f.file.delete()}
    }
}
