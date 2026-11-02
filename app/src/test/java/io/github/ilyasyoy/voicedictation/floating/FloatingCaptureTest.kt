package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingCaptureTest {
    private fun editor() = EditorToken("fixture.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
    @Test fun invalidEnvironmentNeverStartsLocalAudio() = runTest {
        val original=editor(); val valid=FloatingEnvironment(original,true,true,true,true,true)
        for(now in listOf(valid.copy(editor=null),valid.copy(enabled=false),valid.copy(awake=false),valid.copy(keyboard=false),
            valid.copy(microphone=false),valid.copy(notifications=false),valid.copy(editor=original.copy(selectionStart=8,selectionEnd=8)))) {
            var starts=0;var invalid=0
            val session=DictationSession(backgroundScope,object:AudioRecorder {
                override fun start(onLimit:()->Unit) {starts++}
                override fun stop():File=error("no capture")
                override fun cancel()=Unit
            },Transcriber {_,_->error("no HTTP")})
            val capture=FloatingCapture(backgroundScope,session,original,{now},{invalid++},{})
            capture.start();runCurrent();assertEquals(0,starts);assertEquals(1,invalid);capture.close()
        }
    }
    @Test fun capturesImmediatelyBeforeKeyReadAndUploadsOnceOnlyAfterStop() = runTest {
        val loaded=CompletableDeferred<String?>(); var starts=0;var uploads=0
        val file=File.createTempFile("floating-record-",".m4a").apply {writeText("synthetic audio")}
        val session=DictationSession(backgroundScope,object:AudioRecorder {
            override fun start(onLimit:()->Unit) {starts++}
            override fun stop()=file
            override fun cancel()=Unit
        },Transcriber {_,_->uploads++;"Recorded words"},keyStore=object:ApiKeyStore {
            override suspend fun load()=loaded.await()
            override suspend fun save(key:String)=Unit
            override suspend fun forget()=Unit
        })
        val capture=FloatingCapture(backgroundScope,session,editor(),{FloatingEnvironment(editor(),true,true,true,true,true)},{fail("eligible")},{})
        try {
            capture.start();runCurrent();assertEquals(1,starts);assertEquals(Phase.RECORDING,session.state.value.phase);assertEquals(0,uploads)
            session.stop();session.stop();runCurrent();assertEquals(0,uploads)
            loaded.complete("synthetic");runCurrent();assertEquals(1,uploads);assertEquals(Phase.RESULT,session.state.value.phase)
        } finally {capture.close();file.delete()}
    }
    @Test fun closeBeforeKeyCompletionCannotRestartOrNotifyTheClosedOwner() = runTest {
        val loaded=CompletableDeferred<String?>();var starts=0;var states=0
        val session=DictationSession(backgroundScope,object:AudioRecorder {
            override fun start(onLimit:()->Unit) {starts++}
            override fun stop():File=error("not stopped in fixture")
            override fun cancel()=Unit
        },Transcriber {_,_->error("no HTTP")},keyStore=object:ApiKeyStore {
            override suspend fun load()=loaded.await()
            override suspend fun save(key:String)=Unit
            override suspend fun forget()=Unit
        })
        val capture=FloatingCapture(backgroundScope,session,editor(),{FloatingEnvironment(editor(),true,true,true,true,true)},{fail("eligible")},{states++})
        capture.start();runCurrent();val observed=states;capture.close();capture.close()
        loaded.complete("synthetic");runCurrent();assertEquals(1,starts);assertEquals(observed,states);assertEquals("",session.state.value.key)
    }
    @Test fun credentialFailureDuringCaptureKeepsMicrophoneUntilUserStopWithoutHttp() = runTest {
        val loaded=CompletableDeferred<String?>();val file=File.createTempFile("offline-floating-",".m4a").apply {writeText("audio")}
        val session=DictationSession(backgroundScope,object:AudioRecorder {
            override fun start(onLimit:()->Unit)=Unit
            override fun stop()=file
            override fun cancel()=Unit
        },Transcriber {_,_->error("no HTTP")},keyStore=object:ApiKeyStore {
            override suspend fun load()=loaded.await()
            override suspend fun save(key:String)=Unit
            override suspend fun forget()=Unit
        })
        val capture=FloatingCapture(backgroundScope,session,editor(),{FloatingEnvironment(editor(),true,true,true,true,true)},{fail("eligible")},{})
        try {
            capture.start();runCurrent();loaded.completeExceptionally(IOException("fixture"));runCurrent()
            assertEquals(Phase.RECORDING,session.state.value.phase);session.stop();runCurrent()
            assertEquals(Problem.STORAGE,session.state.value.problem);assertTrue(file.exists());assertTrue(session.state.value.canRetry)
        } finally {capture.close();file.delete()}
    }
}
