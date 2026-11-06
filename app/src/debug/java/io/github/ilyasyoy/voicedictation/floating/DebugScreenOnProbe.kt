package io.github.ilyasyoy.voicedictation.floating

import android.content.Context
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

/** Explicit isolated-fixture opt-in only. Real accessibility window / capture lifecycle,
 * synthetic recorder and deferred transcription. Never uses a microphone, key store or HTTP.
 * Kept outside target instrumentation because am instrument force-stops the bound service.
 */
internal object DebugScreenOnProbe {
    private var capture: FloatingCapture? = null
    private var window: FloatingWindow? = null
    private var work: CoroutineScope? = null
    private var answer: CompletableDeferred<String>? = null
    private var file: File? = null
    fun command(context: Context, command: String) {
        check(DebugFloatingConfiguration.notice(context) != null)
        if(command=="close") {close();return}
        if(command=="start") {
            close()
            val service=checkNotNull(FloatingDictationService.instance)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).also {work=it}
            val clip=File.createTempFile("screen-probe-",".m4a",context.cacheDir).also {file=it;it.writeText("synthetic test only")}
            val response=CompletableDeferred<String>().also {answer=it}
            val session=DictationSession(scope,object:AudioRecorder {
                override fun start(onLimit:()->Unit)=Unit
                override fun stop()=clip
                override fun cancel()=Unit
            },Transcriber {_,_->response.await()})
            session.setKey("synthetic-screen-probe")
            val editor=EditorToken("fixture.editor",1,"body",1,7,7,false,false,0x20001,0,-1,-1)
            val native=FloatingWindow(service,{}, {}, {}, {}, {}).also {window=it}
            val imeTop=service.resources.displayMetrics.heightPixels*2/3
            val owner=FloatingCapture(scope,session,editor,{FloatingEnvironment(editor,true,true,true,true,true)},{close()},
                onState={state ->
                    native.render(imeTop,false,"","Synthetic screen lifetime test",state.audioBusy,false,false,null,
                        processing=state.phase==Phase.TRANSCRIBING,error=state.phase==Phase.ERROR,
                        keepScreenOn=capture?.keepsScreenOn==true)
                },onScreenOn=native::setScreenOn)
            capture=owner
            owner.start()
            return
        }
        val session=checkNotNull(capture).session
        when(command) {
            "stop" -> session.stop()
            "success" -> checkNotNull(answer).complete("Synthetic screen test result")
            "error" -> checkNotNull(answer).completeExceptionally(IOException("Synthetic network failure"))
            "cancel" -> session.cancel()
            else -> error("Unknown screen probe command")
        }
    }
    private fun close() {
        try {capture?.close()} finally {
            capture=null;window?.hide();window=null;work?.cancel();work=null
            answer=null;file?.delete();file=null
        }
    }
}
